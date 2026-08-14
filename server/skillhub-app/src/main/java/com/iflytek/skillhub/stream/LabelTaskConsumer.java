package com.iflytek.skillhub.stream;

import com.iflytek.skillhub.domain.label.LabelTask;
import com.iflytek.skillhub.domain.label.LabelTaskProducer;
import com.iflytek.skillhub.domain.label.SkillLabelService;
import com.iflytek.skillhub.service.label.LabelAutoTaggingService;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Consumes {@link LabelTask}s from the auto-tagging stream: asks the LLM for scenario categories,
 * then persists the valid slugs via {@link SkillLabelService#autoTag}.
 *
 * <p>Tagging is best-effort — a permanent failure only logs, never mutates skill state, so a
 * misbehaving LLM or a missing API key can never block a publish.
 */
public class LabelTaskConsumer extends AbstractStreamConsumer<LabelTaskConsumer.LabelTaskPayload> {

    private final LabelTaskProducer labelTaskProducer;
    private final LabelAutoTaggingService labelAutoTaggingService;
    private final SkillLabelService skillLabelService;

    public LabelTaskConsumer(RedissonClient redissonClient,
                             String streamKey,
                             String groupName,
                             LabelTaskProducer labelTaskProducer,
                             LabelAutoTaggingService labelAutoTaggingService,
                             SkillLabelService skillLabelService) {
        super(redissonClient, streamKey, groupName);
        this.labelTaskProducer = labelTaskProducer;
        this.labelAutoTaggingService = labelAutoTaggingService;
        this.skillLabelService = skillLabelService;
    }

    public LabelTaskConsumer(RedissonClient redissonClient,
                             String streamKey,
                             String groupName,
                             LabelTaskProducer labelTaskProducer,
                             LabelAutoTaggingService labelAutoTaggingService,
                             SkillLabelService skillLabelService,
                             boolean reclaimEnabled,
                             Duration reclaimMinIdle,
                             int reclaimBatchSize,
                             Duration reclaimInterval) {
        super(redissonClient, streamKey, groupName, reclaimEnabled, reclaimMinIdle, reclaimBatchSize, reclaimInterval);
        this.labelTaskProducer = labelTaskProducer;
        this.labelAutoTaggingService = labelAutoTaggingService;
        this.skillLabelService = skillLabelService;
    }

    @Override
    protected String taskDisplayName() {
        return "Skill Auto-Tagging";
    }

    @Override
    protected String consumerPrefix() {
        return "labeler";
    }

    @Override
    protected LabelTaskPayload parsePayload(String messageId, Map<String, String> data) {
        String skillId = data.get("skillId");
        if (skillId == null || skillId.isEmpty()) {
            return null;
        }
        try {
            return new LabelTaskPayload(
                    data.get("taskId"),
                    Long.valueOf(skillId),
                    data.get("skillName"),
                    data.get("summary"),
                    data.get("bodySample"),
                    data.get("operatorId"),
                    parseRetryCount(data)
            );
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    protected String payloadIdentifier(LabelTaskPayload payload) {
        return "taskId=" + payload.taskId() + ", skillId=" + payload.skillId();
    }

    @Override
    protected void markProcessing(LabelTaskPayload payload) {
        log.info("Processing auto-tag task: taskId={}, skillId={}, retryCount={}",
                payload.taskId(), payload.skillId(), payload.retryCount());
    }

    @Override
    protected void processBusiness(LabelTaskPayload payload) {
        List<String> slugs;
        try {
            slugs = labelAutoTaggingService.suggestLabels(
                    payload.skillName(), payload.summary(), payload.bodySample());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Auto-tag LLM call failed for skillId=" + payload.skillId() + ": " + e.getMessage(), e);
        }
        if (slugs.isEmpty()) {
            log.info("No scenario labels suggested: skillId={}, skillName={}", payload.skillId(), payload.skillName());
            return;
        }
        skillLabelService.autoTag(payload.skillId(), slugs, payload.operatorId());
    }

    @Override
    protected void markCompleted(LabelTaskPayload payload) {
        log.debug("Auto-tag task completed: taskId={}, skillId={}", payload.taskId(), payload.skillId());
    }

    @Override
    protected void markFailed(LabelTaskPayload payload, String error) {
        log.warn("Auto-tag task failed permanently: taskId={}, skillId={}, error={}",
                payload.taskId(), payload.skillId(), error);
    }

    @Override
    protected void retryMessage(LabelTaskPayload payload, int retryCount) {
        log.warn("Retrying auto-tag task: taskId={}, skillId={}, nextRetryCount={}",
                payload.taskId(), payload.skillId(), retryCount);
        labelTaskProducer.publishLabelTask(new LabelTask(
                payload.taskId(),
                payload.skillId(),
                payload.skillName(),
                payload.summary(),
                payload.bodySample(),
                payload.operatorId(),
                System.currentTimeMillis(),
                Map.of("retryCount", String.valueOf(retryCount))
        ));
    }

    protected static final class LabelTaskPayload {
        private final String taskId;
        private final Long skillId;
        private final String skillName;
        private final String summary;
        private final String bodySample;
        private final String operatorId;
        private final int retryCount;

        protected LabelTaskPayload(String taskId,
                                   Long skillId,
                                   String skillName,
                                   String summary,
                                   String bodySample,
                                   String operatorId,
                                   int retryCount) {
            this.taskId = taskId;
            this.skillId = skillId;
            this.skillName = skillName;
            this.summary = summary;
            this.bodySample = bodySample;
            this.operatorId = operatorId;
            this.retryCount = retryCount;
        }

        protected String taskId() {
            return taskId;
        }

        protected Long skillId() {
            return skillId;
        }

        protected String skillName() {
            return skillName;
        }

        protected String summary() {
            return summary;
        }

        protected String bodySample() {
            return bodySample;
        }

        protected String operatorId() {
            return operatorId;
        }

        protected int retryCount() {
            return retryCount;
        }
    }
}
