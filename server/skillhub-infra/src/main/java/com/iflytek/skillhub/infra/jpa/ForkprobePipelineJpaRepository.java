package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.forkprobe.ForkprobePipeline;
import com.iflytek.skillhub.domain.forkprobe.ForkprobePipelineRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * JPA-backed repository for persisted forkprobe pipeline (编排) runs. Derived
 * query methods fulfil the {@link ForkprobePipelineRepository} domain contract.
 */
@Repository
public interface ForkprobePipelineJpaRepository
        extends JpaRepository<ForkprobePipeline, Long>, ForkprobePipelineRepository {

    Optional<ForkprobePipeline> findByPipelineId(String pipelineId);

    List<ForkprobePipeline> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
