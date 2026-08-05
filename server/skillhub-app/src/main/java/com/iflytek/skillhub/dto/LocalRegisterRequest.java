package com.iflytek.skillhub.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LocalRegisterRequest(
    @NotBlank(message = "{validation.auth.local.username.notBlank}")
    String username,
    @NotBlank(message = "{validation.auth.local.password.notBlank}")
    String password,
    @NotBlank(message = "{validation.auth.local.email.notBlank}")
    @Email(message = "{validation.auth.local.email.invalid}")
    String email,
    @NotBlank(message = "{validation.auth.local.activation.code.notBlank}")
    @Pattern(regexp = "^\\d{6}$", message = "{validation.auth.local.activation.code.invalid}")
    String code
) {}
