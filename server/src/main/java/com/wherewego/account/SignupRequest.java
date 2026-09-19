package com.wherewego.account;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * 회원가입 입력.
 *
 * <p>화면에서 받는 네 칸이 그대로 여기다 — 닉네임, 로그인 아이디, 비밀번호, 비밀번호 확인.
 *
 * <h2>비밀번호 확인을 서버도 보는 이유</h2>
 *
 * 화면에서 이미 맞춰 보지만 서버가 다시 본다. API 는 화면 없이도 호출되고, 브라우저 검사는
 * 사용자 편의지 <b>검증이 아니다</b>. 오타가 그대로 해시로 굳으면 본인도 못 들어온다.
 */
public record SignupRequest(
        @NotBlank(message = "닉네임을 입력해 주세요")
                @Size(min = 2, max = 20, message = "닉네임은 2~20자입니다")
                String nickname,
        @NotBlank(message = "아이디를 입력해 주세요")
                @Pattern(
                        regexp = "^[A-Za-z0-9_]{4,20}$",
                        message = "아이디는 영문·숫자·밑줄 4~20자입니다")
                String loginId,
        @NotBlank(message = "비밀번호를 입력해 주세요")
                // 위쪽 한계는 BCrypt 가 72바이트까지만 보기 때문이다. 그보다 긴 부분은
                // 조용히 잘려 나가므로 "긴 비밀번호를 썼는데 짧은 것과 같아지는" 일이 생긴다.
                @Size(min = 8, max = 64, message = "비밀번호는 8~64자입니다")
                String password,
        @NotBlank(message = "비밀번호 확인을 입력해 주세요") String passwordConfirm) {

    @AssertTrue(message = "비밀번호가 서로 다릅니다")
    public boolean isPasswordConfirmed() {
        return password != null && Objects.equals(password, passwordConfirm);
    }

    /** 로그인 아이디는 소문자로 맞춘다 — 대소문자만 다른 계정이 생기지 않게. */
    String normalizedLoginId() {
        return loginId == null ? null : loginId.toLowerCase();
    }

    /** 닉네임의 앞뒤 공백은 지운다. 보이는 이름의 대소문자는 건드리지 않는다. */
    String normalizedNickname() {
        return nickname == null ? null : nickname.strip();
    }
}
