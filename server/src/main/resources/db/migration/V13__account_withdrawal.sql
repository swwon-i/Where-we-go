-- V13 — 탈퇴
--
-- 계정을 지우면서 방과 후보는 남긴다. 한 사람이 떠난다고 **여럿이 함께 만든 것**이
-- 사라지면 안 된다 — 후보 목록은 그 모임의 공동 작업이고, 이미 본 계산 결과와도 어긋난다.
--
-- 그래서 행을 지우지 않고 식별자만 지운다. 로그인 아이디는 deleted_<id> 로 바꿔
-- 다시 들어올 길을 없애고(원래 아이디는 풀려 다른 사람이 쓸 수 있다), 닉네임은
-- '탈퇴한 사용자' 가 된다. 방 화면의 "…님이 담음" 은 그대로 보인다.


ALTER TABLE app_user ADD COLUMN deleted_at TIMESTAMPTZ;

-- 닉네임 유니크를 **살아 있는 계정에만** 건다.
--
-- 두 가지가 걸려 있다. 탈퇴자가 둘 이상이면 '탈퇴한 사용자' 가 겹치고, 쓰던 닉네임을
-- 계속 붙들고 있으면 남이 그 이름을 못 쓴다. 부분 인덱스가 둘을 한꺼번에 푼다.
DROP INDEX uq_app_user_nickname;
CREATE UNIQUE INDEX uq_app_user_nickname ON app_user (lower(nickname))
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN app_user.deleted_at IS
    '탈퇴 시각. 채워져 있으면 login_id 와 nickname 은 이미 지워진 자리표시다.';
