-- V8 — 방 · 참가자 · 북마크
--
-- 계획서 §4.2 는 인증이 없다는 전제로 쓰였다. 방 접근을 roomId(UUID) 소지로,
-- 본인 확인을 입장 때 발급한 memberId 소지로 하는 구조였다.
-- V7 에서 계정이 생겼으므로 본인 확인은 세션이 한다. 소지 기반 비밀값이 사라져
-- "타인의 memberId 를 응답에 넣지 말 것"이라는 제약도 함께 없어진다.


-- ─────────────────────────────────────────────────────────────────────────────
-- 방
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE room (
    -- UUID 다. 연번이면 남의 방 번호를 추측해 열어볼 수 있다.
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT NOT NULL,
    owner_id    BIGINT NOT NULL REFERENCES app_user (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_room_title CHECK (length(btrim(title)) BETWEEN 1 AND 50)
);

CREATE INDEX ix_room_owner ON room (owner_id);


-- ─────────────────────────────────────────────────────────────────────────────
-- 참가자
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE room_member (
    id          BIGSERIAL PRIMARY KEY,
    room_id     UUID NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    user_id     BIGINT NOT NULL REFERENCES app_user (id),
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- ── 출발지 ────────────────────────────────────────────────────────────
    -- 행렬의 행이 된다. 아직 안 정한 참가자는 전부 NULL 이다.
    origin_label    TEXT,                    -- 사용자가 본 이름 ('강남역', '우리집')
    origin_geom     geometry(Point, 5186),

    -- 스냅 결과. 숨기지 않고 응답에 그대로 내보낸다 — 스냅 거리가 크면
    -- 계산 결과를 덜 믿어야 한다는 신호이고, 그것을 아는 것은 사용자의 권리다.
    --
    -- origin_node_id 에 외래키를 걸지 않는다. graph_node 는 빌드마다 통째로
    -- 갈리고 오래된 빌드는 지워진다. 외래키를 걸면 그래프를 새로 만들 때마다
    -- 참가자의 출발지가 함께 지워지거나 삭제가 막힌다.
    -- 대신 origin_build_id 를 같이 두고, 활성 빌드와 다르면 다시 스냅한다.
    origin_node_id  BIGINT,
    origin_snap_m   REAL,
    origin_build_id BIGINT,
    origin_set_at   TIMESTAMPTZ,

    -- 한 사람이 같은 방에 두 번 들어가지 않는다.
    CONSTRAINT uq_room_member UNIQUE (room_id, user_id),

    -- 출발지는 통째로 있거나 통째로 없다. 좌표만 있고 스냅이 없는 중간 상태를 막는다.
    CONSTRAINT ck_room_member_origin CHECK (
        (origin_geom IS NULL AND origin_node_id IS NULL AND origin_build_id IS NULL)
        OR (origin_geom IS NOT NULL AND origin_node_id IS NOT NULL
            AND origin_build_id IS NOT NULL)
    )
);

CREATE INDEX ix_room_member_room ON room_member (room_id);
CREATE INDEX ix_room_member_user ON room_member (user_id);

COMMENT ON COLUMN room_member.origin_build_id IS
    '스냅할 때의 그래프 빌드. 활성 빌드와 다르면 행렬 계산 전에 재스냅한다.';


-- ─────────────────────────────────────────────────────────────────────────────
-- 북마크 (후보 장소)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE bookmark (
    id          BIGSERIAL PRIMARY KEY,
    room_id     UUID NOT NULL REFERENCES room (id) ON DELETE CASCADE,
    added_by    BIGINT NOT NULL REFERENCES app_user (id),

    -- 우리 POI 에서 고른 경우에만 채워진다. 지도를 찍어 넣으면 NULL 이다.
    -- 외래키를 걸되 삭제는 막지 않는다 — poi 는 소프트 삭제라 행이 사라지지 않는다.
    poi_id      BIGINT REFERENCES poi (id),

    -- ── 추가 시점 스냅샷 ──────────────────────────────────────────────────
    -- poi_id 가 있어도 이름과 좌표를 여기에 복사해 둔다.
    -- 그 장소가 나중에 폐업 처리되거나 원본 파일에서 사라져도 방 화면과
    -- 이미 계산된 행렬이 깨지지 않아야 한다. 방은 그 가게가 아니라
    -- **그날 그 자리를 후보로 골랐다는 사실**을 기억하는 것이다.
    name        TEXT NOT NULL,
    geom        geometry(Point, 5186) NOT NULL,
    address     TEXT,

    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_bookmark_name CHECK (length(btrim(name)) BETWEEN 1 AND 100)
);

CREATE INDEX ix_bookmark_room ON bookmark (room_id);
-- 같은 장소를 두 번 담지 않는다. 지도 클릭(poi_id IS NULL)에는 적용하지 않는다.
CREATE UNIQUE INDEX uq_bookmark_room_poi ON bookmark (room_id, poi_id)
    WHERE poi_id IS NOT NULL;

COMMENT ON COLUMN bookmark.name IS
    '추가 시점 이름. poi.name 이 나중에 바뀌어도 따라가지 않는다.';
