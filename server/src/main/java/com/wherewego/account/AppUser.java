package com.wherewego.account;

import java.time.Instant;

/**
 * 계정 한 건. <b>이 타입이 응답으로 나가는 일은 없다</b> — 해시가 딸려 나가기 때문이다.
 * 밖으로는 {@link AccountController.Me} 만 나간다.
 *
 * @param loginId 소문자로 정규화된 로그인 식별자
 * @param nickname 다른 참가자에게 보이는 이름. 입력한 대소문자 그대로다
 * @param passwordHash BCrypt 해시
 */
record AppUser(long id, String loginId, String nickname, String passwordHash, Instant createdAt) {}
