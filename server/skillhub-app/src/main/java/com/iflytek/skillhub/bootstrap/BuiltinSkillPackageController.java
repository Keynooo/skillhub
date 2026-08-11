package com.iflytek.skillhub.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Serves built-in skill package zips from the local dist directory so dev
 * environments do not need an external CDN or HTTP server.
 * <p>
 * Packages are expected under {@code builtin-skills/dist/} in the project root.
 */
@RestController
class BuiltinSkillPackageController {

    private static final Logger log = LoggerFactory.getLogger(BuiltinSkillPackageController.class);

    private final Path packageDir;

    public BuiltinSkillPackageController(BuiltinSkillProperties properties) {
        String dir = properties.getDevPackageDir();
        this.packageDir = (dir != null && !dir.isBlank())
                ? Path.of(dir)
                : Path.of("builtin-skills/dist");
    }

    @GetMapping("/api/builtin-skills/packages/{filename}")
    public ResponseEntity<byte[]> serve(@PathVariable String filename) {
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            return ResponseEntity.badRequest().build();
        }
        var file = packageDir.resolve(filename).toFile();
        if (!file.isFile()) {
            log.warn("Built-in skill package not found: {}", file);
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS))
                    .body(bytes);
        } catch (IOException e) {
            log.error("Failed to read built-in skill package: {}", filename, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
