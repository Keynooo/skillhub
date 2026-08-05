package com.iflytek.skillhub.infra.jpa;

import com.iflytek.skillhub.domain.auth.AccountActivationRequest;
import com.iflytek.skillhub.domain.auth.AccountActivationRequestRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA-backed repository for account-activation verification-code requests.
 */
public interface AccountActivationRequestJpaRepository
        extends JpaRepository<AccountActivationRequest, Long>, AccountActivationRequestRepository {

    List<AccountActivationRequest> findByUserIdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String userId,
            Instant now
    );

    List<AccountActivationRequest> findByEmailAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String email,
            Instant now
    );
}
