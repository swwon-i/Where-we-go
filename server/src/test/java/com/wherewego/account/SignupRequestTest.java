package com.wherewego.account;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 회원가입 입력 규칙. DB 없이 돈다. */
class SignupRequestTest {

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    private static SignupRequest valid() {
        return new SignupRequest("홍길동", "hong_gd", "s3cret-pw", "s3cret-pw");
    }

    private java.util.Set<String> violatedFields(SignupRequest request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("올바른 입력은 통과한다")
    void validPasses() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    @DisplayName("비밀번호 확인이 다르면 막는다 — 오타가 해시로 굳으면 본인도 못 들어온다")
    void passwordMismatchIsRejected() {
        var request = new SignupRequest("홍길동", "hong_gd", "s3cret-pw", "s3cret-pX");
        assertThat(violatedFields(request)).contains("passwordConfirmed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "한", ""})
    @DisplayName("닉네임은 2자 이상이다")
    void nicknameTooShort(String nickname) {
        assertThat(violatedFields(new SignupRequest(nickname, "hong_gd", "s3cret-pw", "s3cret-pw")))
                .contains("nickname");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "홍길동!", "hong gd", "hong-gd", "한글아이디"})
    @DisplayName("아이디는 영문·숫자·밑줄 4~20자다")
    void loginIdFormat(String loginId) {
        assertThat(violatedFields(new SignupRequest("홍길동", loginId, "s3cret-pw", "s3cret-pw")))
                .contains("loginId");
    }

    @Test
    @DisplayName("비밀번호는 8자 이상이다")
    void passwordTooShort() {
        assertThat(violatedFields(new SignupRequest("홍길동", "hong_gd", "short7c", "short7c")))
                .contains("password");
    }

    @Test
    @DisplayName("비밀번호 64자 상한 — BCrypt 가 72바이트까지만 보므로 뒤가 조용히 잘린다")
    void passwordTooLong() {
        String long65 = "a".repeat(65);
        assertThat(violatedFields(new SignupRequest("홍길동", "hong_gd", long65, long65)))
                .contains("password");
    }

    @Test
    @DisplayName("아이디는 소문자로 맞춘다 — 대소문자만 다른 계정이 생기지 않게")
    void loginIdIsLowercased() {
        var request = new SignupRequest("홍길동", "Hong_GD", "s3cret-pw", "s3cret-pw");
        assertThat(request.normalizedLoginId()).isEqualTo("hong_gd");
    }

    @Test
    @DisplayName("닉네임은 앞뒤 공백만 지우고 대소문자는 그대로 둔다 — 보이는 이름이다")
    void nicknameKeepsCase() {
        var request = new SignupRequest("  Hong GilDong  ", "hong_gd", "s3cret-pw", "s3cret-pw");
        assertThat(request.normalizedNickname()).isEqualTo("Hong GilDong");
    }
}
