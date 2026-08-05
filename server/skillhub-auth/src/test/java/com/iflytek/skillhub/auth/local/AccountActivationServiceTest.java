package com.iflytek.skillhub.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;

import com.iflytek.skillhub.auth.exception.AuthFlowException;
import com.iflytek.skillhub.domain.auth.AccountActivationRequest;
import com.iflytek.skillhub.domain.auth.AccountActivationRequestRepository;
import com.iflytek.skillhub.domain.user.UserAccount;
import com.iflytek.skillhub.domain.user.UserAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AccountActivationServiceTest {

    @Mock
    private AccountActivationRequestRepository activationRequestRepository;

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JavaMailSender mailSender;

    private AccountActivationService service;

    @BeforeEach
    void setUp() {
        PasswordResetProperties properties = new PasswordResetProperties();
        properties.setCodeExpiry(Duration.ofMinutes(10));
        properties.setEmailFromAddress("noreply@skillhub.local");
        properties.setEmailFromName("SkillHub");
        service = new AccountActivationService(
                activationRequestRepository, userAccountRepository, passwordEncoder, mailSender, properties);
    }

    @Test
    void sendRegistrationCode_savesAndSendsEmail() {
        given(userAccountRepository.findByEmailIgnoreCase("alice@example.com")).willReturn(Optional.empty());
        given(activationRequestRepository.findByEmailAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                anyString(), any(Instant.class))).willReturn(List.of());
        given(passwordEncoder.encode(anyString())).willReturn("encoded");

        service.sendRegistrationCode("alice@example.com");

        verify(activationRequestRepository).save(any(AccountActivationRequest.class));
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendRegistrationCode_withRegisteredEmail_throwsConflict() {
        given(userAccountRepository.findByEmailIgnoreCase("alice@example.com")).willReturn(
                Optional.of(new UserAccount("usr_1", "alice", "alice@example.com", null)));

        assertThatThrownBy(() -> service.sendRegistrationCode("alice@example.com"))
                .isInstanceOf(AuthFlowException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void sendRegistrationCode_emailFailure_throws() {
        given(userAccountRepository.findByEmailIgnoreCase("alice@example.com")).willReturn(Optional.empty());
        given(activationRequestRepository.findByEmailAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                anyString(), any(Instant.class))).willReturn(List.of());
        given(passwordEncoder.encode(anyString())).willReturn("encoded");
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> service.sendRegistrationCode("alice@example.com"))
                .isInstanceOf(AuthFlowException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void verifyAndConsumeCode_withValidCode_consumes() {
        AccountActivationRequest request = new AccountActivationRequest(
                null, "alice@example.com", "encoded-code", Instant.now().plus(Duration.ofMinutes(5)));
        given(activationRequestRepository.findByEmailAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                anyString(), any(Instant.class)))
            .willReturn(List.of(request))
            .willReturn(List.of());
        given(passwordEncoder.matches("123456", "encoded-code")).willReturn(true);

        service.verifyAndConsumeCode("alice@example.com", "123456");

        assertThat(request.getConsumedAt()).isNotNull();
        verify(activationRequestRepository).save(eq(request));
    }

    @Test
    void verifyAndConsumeCode_withInvalidCode_throwsBadRequest() {
        AccountActivationRequest request = new AccountActivationRequest(
                null, "alice@example.com", "encoded-code", Instant.now().plus(Duration.ofMinutes(5)));
        given(activationRequestRepository.findByEmailAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                anyString(), any(Instant.class))).willReturn(List.of(request));
        given(passwordEncoder.matches("654321", "encoded-code")).willReturn(false);

        assertThatThrownBy(() -> service.verifyAndConsumeCode("alice@example.com", "654321"))
                .isInstanceOf(AuthFlowException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
