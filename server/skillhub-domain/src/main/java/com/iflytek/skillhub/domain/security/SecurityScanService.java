package com.iflytek.skillhub.domain.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iflytek.skillhub.domain.skill.SkillVisibility;
import com.iflytek.skillhub.domain.skill.SkillVersion;
import com.iflytek.skillhub.domain.skill.SkillVersionRepository;
import com.iflytek.skillhub.domain.skill.SkillVersionStatus;
import com.iflytek.skillhub.domain.skill.validation.PackageEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SecurityScanService {

    private static final Logger log = LoggerFactory.getLogger(SecurityScanService.class);
    private static final String TEMP_DIR = "/tmp/skillhub-scans";
    private static final Path TEMP_BASE_DIR = Paths.get(TEMP_DIR).toAbsolutePath().normalize();

    private final SecurityAuditRepository auditRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final ScanTaskProducer scanTaskProducer;
    private final ObjectMapper objectMapper;
    private final String scanMode;
    private final boolean enabled;

    public SecurityScanService(SecurityAuditRepository auditRepository,
                               SkillVersionRepository skillVersionRepository,
                               ScanTaskProducer scanTaskProducer,
                               ObjectMapper objectMapper,
                               @Value("${skillhub.security.scanner.mode:local}") String scanMode,
                               @Value("${skillhub.security.scanner.enabled:false}") boolean enabled) {
        this.auditRepository = auditRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.scanTaskProducer = scanTaskProducer;
        this.objectMapper = objectMapper;
        this.scanMode = scanMode;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional
    public void triggerScan(Long versionId, List<PackageEntry> entries, String publisherId) {
        if (!enabled) {
            log.debug("Security scanner disabled, skipping trigger for versionId={}", versionId);
            return;
        }

        SkillVersion version = skillVersionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("SkillVersion not found: " + versionId));

        String packagePath = null;
        String bundleKey = null;
        if ("upload".equalsIgnoreCase(scanMode)) {
            validateUploadEntries(entries);
            bundleKey = buildBundleStorageKey(version.getSkillId(), versionId);
        } else {
            packagePath = saveTempDirectory(versionId, entries).toString();
        }
        // Always create a new audit record — supports multiple rounds per version
        auditRepository.save(new SecurityAudit(versionId, ScannerType.SKILL_SCANNER));
        ScanTask task = new ScanTask(
                UUID.randomUUID().toString(),
                versionId,
                packagePath,
                bundleKey,
                publisherId,
                System.currentTimeMillis(),
                Map.of("scannerType", ScannerType.SKILL_SCANNER.getValue())
        );
        // The placeholder audit above is only visible to other transactions after this one
        // commits. Publishing inside the transaction lets a fast consumer process the task
        // before commit and fail with "SecurityAudit not found" (observed on 2026-09-04,
        // versionId=72 stuck in SCANNING). Defer the publish until afterCommit.
        publishAfterCommit(task);
        // Only transition to SCANNING if the version is not already published (auto-publish flow)
        if (version.getStatus() != SkillVersionStatus.PUBLISHED) {
            version.setStatus(SkillVersionStatus.SCANNING);
            skillVersionRepository.save(version);
        }
    }

    @Transactional
    public void processScanResult(Long versionId, ScannerType scannerType, SecurityScanResponse response) {
        SecurityAudit audit = auditRepository.findLatestActiveByVersionIdAndScannerType(versionId, scannerType)
                .orElseThrow(() -> new IllegalStateException(
                        "SecurityAudit not found for versionId=" + versionId + ", scannerType=" + scannerType));
        SkillVersion version = skillVersionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalStateException("SkillVersion not found: " + versionId));

        audit.setScanId(response.scanId());
        audit.setVerdict(response.verdict());
        audit.setIsSafe(response.verdict() == SecurityVerdict.SAFE);
        audit.setMaxSeverity(response.maxSeverity());
        audit.setFindingsCount(response.findingsCount());
        audit.setFindings(serializeFindings(response.findings()));
        audit.setScanDurationSeconds(response.scanDurationSeconds());
        audit.setScannedAt(Instant.now(Clock.systemUTC()));
        auditRepository.save(audit);

        // Only transition from SCANNING — leave PUBLISHED/REJECTED/YANKED untouched
        if (version.getStatus() == SkillVersionStatus.SCANNING) {
            if (version.getRequestedVisibility() == SkillVisibility.PRIVATE) {
                version.setStatus(SkillVersionStatus.UPLOADED);
            } else {
                version.setStatus(SkillVersionStatus.PENDING_REVIEW);
            }
        }
        skillVersionRepository.save(version);
    }

    /**
     * Soft-delete the in-progress placeholder audit left behind when a scan fails permanently.
     *
     * <p>{@code triggerScan} always persists a placeholder audit (verdict SUSPICIOUS, no
     * {@code scannedAt}) before publishing the scan task. When the scan later fails permanently,
     * {@code markFailed} can only transition a {@code SCANNING} version to {@code SCAN_FAILED};
     * an already-{@code PUBLISHED} version keeps its status, leaving the orphaned placeholder to
     * render as "scanning" forever. Discarding that placeholder (when it has never been scanned)
     * keeps the audit view honest instead of showing a perpetual "in progress" badge.
     */
    @Transactional
    public void discardFailedPlaceholder(Long versionId, ScannerType scannerType) {
        auditRepository.findLatestActiveByVersionIdAndScannerType(versionId, scannerType)
                .filter(audit -> audit.getScannedAt() == null)
                .ifPresent(audit -> {
                    audit.markAsDeleted();
                    auditRepository.save(audit);
                });
    }

    private void publishAfterCommit(ScanTask task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            scanTaskProducer.publishScanTask(task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    scanTaskProducer.publishScanTask(task);
                } catch (Exception e) {
                    // The transaction is already committed; failing the caller now would be
                    // misleading. The version stays in SCANNING and can be re-triggered.
                    log.error("Failed to publish scan task after commit: taskId={}, versionId={}",
                            task.taskId(), task.versionId(), e);
                }
            }
        });
    }

    private Path saveTempDirectory(Long versionId, List<PackageEntry> entries) {
        try {
            Path skillDir = TEMP_BASE_DIR.resolve(String.valueOf(versionId)).normalize();
            Files.createDirectories(skillDir);
            for (PackageEntry entry : entries) {
                Path filePath = resolveSafeChild(skillDir, entry.path());
                Path parent = filePath.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.write(filePath, entry.content());
            }
            return skillDir;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save temp directory for versionId: " + versionId, e);
        }
    }

    private void validateUploadEntries(List<PackageEntry> entries) {
        for (PackageEntry entry : entries) {
            safeZipEntryName(entry.path());
        }
    }

    private String buildBundleStorageKey(Long skillId, Long versionId) {
        return String.format("packages/%d/%d/bundle.zip", skillId, versionId);
    }

    private String serializeFindings(List<SecurityFinding> findings) {
        try {
            return objectMapper.writeValueAsString(findings);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize findings for security audit", e);
            return "[]";
        }
    }

    private Path resolveSafeChild(Path baseDir, String entryPath) {
        Path resolved = baseDir.resolve(entryPath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalStateException("Unsafe scan path: " + entryPath);
        }
        return resolved;
    }

    private String safeZipEntryName(String entryPath) {
        Path normalized = Paths.get(entryPath).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..")) {
            throw new IllegalStateException("Unsafe scan path: " + entryPath);
        }
        String safePath = normalized.toString().replace('\\', '/');
        if (safePath.isBlank() || safePath.startsWith("../")) {
            throw new IllegalStateException("Unsafe scan path: " + entryPath);
        }
        return safePath;
    }

    /**
     * Soft delete all audit records for a given skill version.
     * Called before physically deleting a skill version to preserve audit history.
     */
    @Transactional
    public void softDeleteByVersionId(Long versionId) {
        List<SecurityAudit> audits = auditRepository.findAllActiveBySkillVersionId(versionId);
        if (audits.isEmpty()) {
            log.debug("No active security audits to soft-delete for versionId={}", versionId);
            return;
        }
        audits.forEach(SecurityAudit::markAsDeleted);
        auditRepository.saveAll(audits);
        log.info("Soft deleted {} security audit(s) for versionId={}", audits.size(), versionId);
    }

    /**
     * Physically delete all audit records for a given skill version.
     * Called during hard delete when the entire skill is being permanently removed.
     */
    @Transactional
    public void hardDeleteByVersionId(Long versionId) {
        auditRepository.deleteBySkillVersionId(versionId);
    }
}
