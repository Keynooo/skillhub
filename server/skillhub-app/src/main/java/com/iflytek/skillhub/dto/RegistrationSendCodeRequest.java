package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegistrationSendCodeRequest(
        @NotBlank(message = "{validation.auth.local.activation.email.notBlank}")
        @Email(message = "{validation.auth.local.activation.email.invalid}")
        @Pattern(regexp = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$",
                message = "{validation.auth.local.activation.email.invalid}")
        String email
) {
}
