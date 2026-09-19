-- V7 — 계정
--
-- 테이블 이름이 app_user 인 것은 user 가 SQL 예약어이기 때문이다.
-- PostgreSQL 에서 "user" 는 현재 사용자를 뜻하는 함수라 매번 따옴표를 붙여야 한다.

CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,

    -- 로그인 식별자. 화면에 노출되지 않는다. 소문자로 정규화해 저장한다.
    login_id        TEXT NOT NULL,
    -- 다른 참가자에게 보이는 이름. 방 화면의 행 제목이 된다.
    -- 입력한 대소문자 그대로 보관한다 — 보이는 이름을 서버가 바꾸면 안 된다.
    nickname        TEXT NOT NULL,

    -- BCrypt 해시. {bcrypt} 접두사가 붙은 형태로 들어간다.
    -- 평문은 어디에도 남기지 않는다 — 로그에도, 이 컬럼에도.
    password_hash   TEXT NOT NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_app_user_login_id UNIQUE (login_id),
    CONSTRAINT ck_app_user_login_id CHECK (login_id = lower(login_id)),
    CONSTRAINT ck_app_user_nickname CHECK (length(nickname) BETWEEN 2 AND 20)
);

-- 닉네임 중복은 **대소문자를 무시하고** 막는다.
-- 'Hong' 과 'hong' 이 서로 다른 계정이면 사람이 둘을 구분하지 못한다. 방 화면에서
-- 누가 누구인지 헷갈리는 순간 이 서비스의 쓸모가 사라진다.
-- 보이는 이름은 입력 그대로 두고 비교만 소문자로 한다.
CREATE UNIQUE INDEX uq_app_user_nickname ON app_user (lower(nickname));

COMMENT ON COLUMN app_user.password_hash IS
    'BCrypt 해시. 길이·형식 검사는 애플리케이션이 한다. 평문은 절대 저장하지 않는다.';
