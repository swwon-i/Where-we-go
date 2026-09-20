-- V9 — 초대 코드
--
-- 지금까지 방에 들어가는 길은 UUID 가 박힌 링크뿐이었다. 카톡으로 보낼 때는 되지만
-- **말로 불러주거나 받아 적을 수가 없다.**
--
--   b788f870-2cff-4282-8750-04af0a58ba20   →   K7MP-3XQR
--
-- UUID 는 그대로 기본키이자 주소로 두고, 코드는 사람이 입력하는 통로를 하나 더 여는 것이다.

ALTER TABLE room ADD COLUMN invite_code TEXT;

-- 이미 있는 방에 코드를 채운다.
--
-- random() 은 암호학적으로 안전하지 않다. 여기서 쓰는 것은 **이 한 번의 채우기**가
-- 추측 대상이 아니기 때문이다(개발 중에 만든 방 몇 개). 앱이 새로 만드는 코드는
-- SecureRandom 을 쓴다 — InviteCode.java 참조.
--
-- 행마다 다른 값이 나오도록 루프를 돈다. UPDATE 한 줄에 서브쿼리를 쓰면 플래너가
-- 한 번만 계산해 모든 행에 같은 코드가 들어갈 수 있다.
DO $$
DECLARE
    target record;
BEGIN
    FOR target IN SELECT id FROM room LOOP
        UPDATE room SET invite_code = (
            SELECT string_agg(
                substr('0123456789ABCDEFGHJKMNPQRSTVWXYZ', floor(random() * 32)::int + 1, 1), '')
            FROM generate_series(1, 8)
        )
        WHERE id = target.id;
    END LOOP;
END $$;

-- NOT NULL 이지만 기본값을 두지 않는다.
--
-- 그래서 **이 마이그레이션은 코드보다 먼저 적용되면 안 된다.** 옛 코드는 invite_code 를
-- 채우지 않으므로 방 만들기가 전부 실패한다(실제로 그렇게 됐다 — flyway 컨테이너를
-- 먼저 돌리고 서버를 나중에 다시 말았더니 500 이 났다).
--
-- 기본값으로 막을 수도 있었다. random() 으로 코드를 만드는 함수를 두고 DEFAULT 로 걸면
-- 옛 코드도 돌아간다. 그렇게 하지 않은 이유는 **그 경로로 만들어진 코드가 약해지기**
-- 때문이다. 코드는 추측을 막는 것이 목적이고 random() 은 암호학적으로 안전하지 않다.
-- 배포 순서는 우리가 지킬 수 있지만, 약한 코드로 만들어진 방은 되돌릴 수 없다.
ALTER TABLE room ALTER COLUMN invite_code SET NOT NULL;

-- 코드는 대문자로 정규화해 저장한다. 조회도 같은 형태로 들어온다.
ALTER TABLE room ADD CONSTRAINT ck_room_invite_code
    CHECK (invite_code ~ '^[0-9A-HJKMNP-TV-Z]{8}$');

CREATE UNIQUE INDEX uq_room_invite_code ON room (invite_code);

COMMENT ON COLUMN room.invite_code IS
    'Crockford Base32 8자. I·L·O·U 를 뺀 32글자라 0/O, 1/I/L 혼동이 없다. '
    '표시할 때만 XXXX-XXXX 로 끊고, 저장·입력에는 하이픈이 없다.';
