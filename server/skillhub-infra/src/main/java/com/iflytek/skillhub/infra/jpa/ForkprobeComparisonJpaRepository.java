package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.forkprobe.ForkprobeComparison;
import com.iflytek.skillhub.domain.forkprobe.ForkprobeComparisonRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA-backed repository for persisted forkprobe comparison runs. Derived query
 * methods fulfil the {@link ForkprobeComparisonRepository} domain contract.
 */
@Repository
public interface ForkprobeComparisonJpaRepository
        extends JpaRepository<ForkprobeComparison, Long>, ForkprobeComparisonRepository {

    Optional<ForkprobeComparison> findByComparisonId(String comparisonId);

    List<ForkprobeComparison> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
