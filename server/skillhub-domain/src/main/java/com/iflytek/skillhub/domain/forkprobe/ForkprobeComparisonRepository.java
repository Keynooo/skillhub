package com.iflytek.skillhub.domain.forkprobe;

import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

/**
 * Domain repository contract for persisting and querying forkprobe comparison
 * runs.
 */
public interface ForkprobeComparisonRepository {

    ForkprobeComparison save(ForkprobeComparison comparison);

    void delete(ForkprobeComparison comparison);

    Optional<ForkprobeComparison> findByComparisonId(String comparisonId);

    /**
     * Recent comparison runs for a user, newest first. {@code pageable} is used
     * only for the {@code limit} (a {@code PageRequest.of(0, limit)}).
     */
    List<ForkprobeComparison> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
