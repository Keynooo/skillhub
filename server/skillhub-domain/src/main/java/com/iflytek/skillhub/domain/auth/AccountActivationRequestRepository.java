package com.iflytek.skillhub.domain.auth;

import java.time.Instant;
import java.util.List;

/**
 * Domain repository contract for local-account email activation verification
 * codes.
 */
public interface AccountActivationRequestRepository {
    AccountActivationRequest save(AccountActivationRequest request);

    List<AccountActivationRequest> findByUserIdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String userId,
            Instant now
    );

    List<AccountActivationRequest> findByEmailAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String email,
            Instant now
    );
}
