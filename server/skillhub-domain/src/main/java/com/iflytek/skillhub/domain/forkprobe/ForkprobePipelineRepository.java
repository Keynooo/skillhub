package com.iflytek.skillhub.domain.forkprobe;

import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * Domain repository contract for persisting and querying forkprobe pipeline
 * (编排) runs.
 */
public interface ForkprobePipelineRepository {

    ForkprobePipeline save(ForkprobePipeline pipeline);

    Optional<ForkprobePipeline> findByPipelineId(String pipelineId);

    /**
     * Recent pipeline runs for a user, newest first. {@code pageable} is used
     * only for the {@code limit} (a {@code PageRequest.of(0, limit)}).
     */
    List<ForkprobePipeline> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
