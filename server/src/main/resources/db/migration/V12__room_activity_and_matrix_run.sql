-- V12 — 방의 마지막 활동 시각 · 행렬 계산 기록


-- ─────────────────────────────────────────────────────────────────────────────
-- 방의 마지막 활동
-- ─────────────────────────────────────────────────────────────────────────────

-- updated_at 이 아니다. 방의 변화는 대부분 room 이 아니라 room_member · bookmark 에서 일어나고,
-- 시스템이 하는 재스냅이나 초대 코드 재발급은 활동으로 치지 않는다.
ALTER TABLE room ADD COLUMN last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- 기존 방을 지금 시각으로 두면 전부 방금 쓴 방처럼 보인다. 남아 있는 기록으로 되짚는다.
-- GREATEST 는 NULL 을 건너뛴다. 지워진 북마크의 시각은 알 수 없으므로 포함되지 않는다.
UPDATE room r
SET last_activity_at = GREATEST(
    r.created_at,
    (SELECT max(GREATEST(m.joined_at, m.origin_set_at)) FROM room_member m WHERE m.room_id = r.id),
    (SELECT max(b.created_at) FROM bookmark b WHERE b.room_id = r.id));

COMMENT ON COLUMN room.last_activity_at IS
    '사람이 한 마지막 활동(입장 · 출발지 · 북마크). 재스냅 같은 시스템 작업에는 바뀌지 않는다.';


-- ─────────────────────────────────────────────────────────────────────────────
-- 행렬 계산 기록
-- ─────────────────────────────────────────────────────────────────────────────

-- 운영 기록이다. 호출 한 번에 한 줄이고 30일 뒤 지운다.
-- 좌표와 사용자는 담지 않는다 — 속도와 품질을 보는 데 필요 없다.
CREATE TABLE matrix_run (
    id              BIGSERIAL PRIMARY KEY,

    -- 외래키를 걸지 않는다. 방이 지워져도 운영 기록은 남아야 한다.
    room_id         UUID NOT NULL,
    graph_build_id  BIGINT,
    status          TEXT NOT NULL,

    origin_count    INT,
    dest_count      INT,
    unreachable     INT,            -- 경로를 찾지 못한 칸
    resnapped       INT,            -- 그래프가 바뀌어 다시 붙인 출발지
    settled_nodes   INT,
    graph_source    TEXT,           -- CACHE: 메모리 · DB: 재시작 뒤 처음 읽음

    snap_ms         BIGINT,
    search_ms       BIGINT,
    elapsed_ms      BIGINT NOT NULL,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_matrix_run_status CHECK (status IN ('SUCCESS', 'NOT_COMPUTABLE', 'ERROR'))
);

CREATE INDEX ix_matrix_run_created ON matrix_run (created_at);
