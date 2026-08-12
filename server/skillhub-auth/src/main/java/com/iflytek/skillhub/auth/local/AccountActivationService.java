package com.iflytek.skillhub.auth.local;

import com.iflytek.skillhub.auth.exception.AuthFlowException;
import com.iflytek.skillhub.domain.auth.AccountActivationRequest;
import com.iflytek.skillhub.domain.auth.AccountActivationRequestRepository;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import com.iflytek.skillhub.mail.ResendEmailSender;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Issues and verifies the one-time email code used to confirm an email address
 * during local-account registration. Codes are looked up by email — the account
 * is only created after the code is verified — so no user id is needed here.
 */
@Service
public class AccountActivationService {

    private static final Logger log = LoggerFactory.getLogger(AccountActivationService.class);
    private static final int VERIFICATION_CODE_DIGITS = 6;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AccountActivationRequestRepository activationRequestRepository;
    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final PasswordResetProperties properties;
    private final ResendEmailSender resendEmailSender;

    public AccountActivationService(AccountActivationRequestRepository activationRequestRepository,
                                    UserAccountRepository userAccountRepository,
                                    PasswordEncoder passwordEncoder,
                                    JavaMailSender mailSender,
                                    PasswordResetProperties properties,
                                    ResendEmailSender resendEmailSender) {
        this.activationRequestRepository = activationRequestRepository;
        this.userAccountRepository = userAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.properties = properties;
        this.resendEmailSender = resendEmailSender;
    }

    /**
     * Emails a fresh registration verification code, invalidating any pending
     * ones. Rejects emails that already belong to an account.
     */
    @Transactional
    public void sendRegistrationCode(String email) {
        String normalizedEmail = normalizeEmail(email);
        validateEmail(normalizedEmail);
        if (userAccountRepository.findByEmailIgnoreCase(normalizedEmail).isPresent()) {
            throw new AuthFlowException(HttpStatus.CONFLICT, "error.auth.local.email.exists");
        }
        invalidatePendingRequestsByEmail(normalizedEmail, Instant.now());
        createAndSend(normalizedEmail);
    }

    /**
     * Verifies a registration code for the given email, consuming it on success.
     * Throws on mismatch/expiry so registration aborts without creating an account.
     */
    @Transactional
    public void verifyAndConsumeCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        validateEmail(normalizedEmail);
        Instant now = Instant.now();
        AccountActivationRequest matched = activationRequestRepository
            .findByEmailAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(normalizedEmail, now)
            .stream()
            .filter(request -> passwordEncoder.matches(code, request.getCodeHash()))
            .findFirst()
            .orElseThrow(this::invalidCode);
        matched.markConsumed(now);
        activationRequestRepository.save(matched);
        invalidatePendingRequestsByEmail(normalizedEmail, now);
    }

    private void createAndSend(String email) {
        String code = generateVerificationCode();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getCodeExpiry());
        activationRequestRepository.save(new AccountActivationRequest(
            null, email, passwordEncoder.encode(code), expiresAt));
        sendVerificationCodeEmail(email, code);
    }

    private void invalidatePendingRequestsByEmail(String email, Instant now) {
        List<AccountActivationRequest> pending = activationRequestRepository
            .findByEmailAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(email, now);
        for (AccountActivationRequest request : pending) {
            request.markConsumed(now);
            activationRequestRepository.save(request);
        }
    }

    private void sendVerificationCodeEmail(String email, String code) {
        if (resendEmailSender.isEnabled()) {
            try {
                resendEmailSender.send(resolveFromAddress(), email,
                        "SkillHub registration verification code",
                        buildVerificationCodeBody(code));
                return;
            } catch (Exception ex) {
                log.error("Resend failed for {} (code: {}), trying SMTP fallback", email, code, ex);
            }
        }
        // SMTP fallback
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(resolveFromAddress());
        message.setTo(email);
        message.setSubject("SkillHub registration verification code");
        message.setText(buildVerificationCodeBody(code));
        try {
            mailSender.send(message);
            log.info("Registration verification code sent to {}", email);
        } catch (Exception ex) {
            log.error("Failed to send registration verification code to {} (code: {})", email, code, ex);
            throw new AuthFlowException(HttpStatus.INTERNAL_SERVER_ERROR, "error.auth.local.activation.email.failed");
        }
    }

    private String resolveFromAddress() {
        String fromAddress = properties.getEmailFromAddress();
        if (!StringUtils.hasText(properties.getEmailFromName())) {
            return fromAddress;
        }
        return properties.getEmailFromName() + " <" + fromAddress + ">";
    }

    private String buildVerificationCodeBody(String code) {
        long expiryMinutes = Math.max(1L, properties.getCodeExpiry().toMinutes());
        return "Your SkillHub registration verification code is: " + code
            + "\n\nThis code expires in " + expiryMinutes + " minutes."
            + "\n\nIf you did not request this, please ignore this email.";
    }

    private String generateVerificationCode() {
        int bound = (int) Math.pow(10, VERIFICATION_CODE_DIGITS);
        int code = SECURE_RANDOM.nextInt(bound);
        return String.format("%0" + VERIFICATION_CODE_DIGITS + "d", code);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void validateEmail(String email) {
        if (email == null) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "validation.auth.local.activation.email.notBlank");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new AuthFlowException(HttpStatus.BAD_REQUEST, "validation.auth.local.activation.email.invalid");
        }
    }

    private AuthFlowException invalidCode() {
        return new AuthFlowException(HttpStatus.BAD_REQUEST, "error.auth.local.activation.invalid.code");
    }
}
