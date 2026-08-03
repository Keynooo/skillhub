package com.iflytek.skillhub.auth.local;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyValidatorTest {

    private final PasswordPolicyValidator validator = new PasswordPolicyValidator();

    @Test
    void validPassword_passes() {
        assertThat(validator.validate("Abcdef1!")).isEmpty();
    }

    @Test
    void tooShort_fails() {
        // MIN_LENGTH=6，5 位应触发 tooShort
        assertThat(validator.validate("Ab1!x")).containsExactly("error.auth.local.password.tooShort");
    }

    @Test
    void tooLong_fails() {
        assertThat(validator.validate("A".repeat(129))).containsExactly("error.auth.local.password.tooLong");
    }

    @Test
    void twoCharTypes_passes() {
        // 规则放宽后，2 种字符类型（小写 + 数字）合法
        assertThat(validator.validate("abcdef1")).isEmpty();
    }

    @Test
    void singleCharType_passes() {
        // 规则放宽后，单一字符类型（纯小写字母）只要长度够 6 位就合法
        assertThat(validator.validate("abcdef")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Abcdefg1", "Abcdef1!", "abcdef1!", "ABCDEF1!"})
    void multiCharTypes_pass(String password) {
        assertThat(validator.validate(password)).isEmpty();
    }
}
