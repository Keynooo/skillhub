package com.iflytek.skillhub.config;

import com.iflytek.skillhub.domain.label.LabelTaggingReviewService;
import com.iflytek.skillhub.domain.label.LabelTaskProducer;
import com.iflytek.skillhub.domain.label.SkillLabelService;
import com.iflytek.skillhub.service.label.LabelAutoTaggingService;
import com.iflytek.skillhub.stream.LabelTaskConsumer;
import com.iflytek.skillhub.stream.RedissonLabelTaskProducer;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the LLM auto-tagging stream pipeline (producer + consumer) for {@link LabelTaskProducer}.
 *
 * <p>Mirrors the security-scan pipeline: when {@code skillhub.label.auto-tagging.enabled} is true a
 * Redis-backed producer and a stream consumer are registered; otherwise a no-op producer is
 * substituted so {@link com.iflytek.skillhub.domain.skill.service.SkillPublishService} can publish
 * unconditionally.
 */
@Configuration
public class LabelStreamConfig {

    @Value("${skillhub.label.auto-tagging.stream.key:skillhub:label:requests}")
    private String streamKey;

    @Value("${skillhub.label.auto-tagging.stream.group:skillhub-labelers}")
    private String groupName;

    @Value("${skillhub.label.auto-tagging.stream.reclaim-enabled:true}")
    private boolean reclaimEnabled;

    @Value("${skillhub.label.auto-tagging.stream.reclaim-min-idle:PT2M}")
    private Duration reclaimMinIdle;

    @Value("${skillhub.label.auto-tagging.stream.reclaim-batch-size:20}")
    private int reclaimBatchSize;

    @Value("${skillhub.label.auto-tagging.stream.reclaim-interval:PT30S}")
    private Duration reclaimInterval;

    @Bean
    @ConditionalOnProperty(prefix = "skillhub.label.auto-tagging", name = "enabled", havingValue = "true")
    public LabelTaskProducer redissonLabelTaskProducer(RedissonClient redissonClient) {
        return new RedissonLabelTaskProducer(redissonClient, streamKey);
    }

    @Bean
    @ConditionalOnMissingBean(LabelTaskProducer.class)
    @ConditionalOnProperty(prefix = "skillhub.label.auto-tagging", name = "enabled", havingValue = "false", matchIfMissing = true)
    public LabelTaskProducer noOpLabelTaskProducer() {
        return task -> {
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "skillhub.label.auto-tagging", name = "enabled", havingValue = "true")
    public LabelTaskConsumer labelTaskConsumer(RedissonClient redissonClient,
                                               LabelTaskProducer labelTaskProducer,
                                               LabelAutoTaggingService labelAutoTaggingService,
                                               SkillLabelService skillLabelService,
                                               LabelTaggingReviewService labelTaggingReviewService) {
        return new LabelTaskConsumer(
                redissonClient,
                streamKey,
                groupName,
                labelTaskProducer,
                labelAutoTaggingService,
                skillLabelService,
                labelTaggingReviewService,
                reclaimEnabled,
                reclaimMinIdle,
                reclaimBatchSize,
                reclaimInterval
        );
    }
}
