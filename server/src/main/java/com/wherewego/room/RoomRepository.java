package com.wherewego.room;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 방·참가자·북마크 조회와 저장.
 *
 * <p>좌표는 저장이 5186 이고 응답은 4326 이다. 변환은 <b>꺼낼 때 SQL 에서 한 번만</b> 한다 —
 * 조건절에 {@code ST_Transform} 을 걸면 공간 인덱스를 못 타므로, 넣을 때도 입력 좌표 쪽을
 * 5186 으로 옮긴다.
 */
@Repository
public class RoomRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public RoomRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 방 ──────────────────────────────────────────────────────────────────

    public record RoomRow(
            UUID id,
            String title,
            long ownerId,
            String ownerNickname,
            String inviteCode,
            java.time.Instant createdAt) {}

    private static final String ROOM_COLUMNS =
            """
            SELECT r.id, r.title, r.owner_id, u.nickname, r.invite_code, r.created_at
            FROM room r JOIN app_user u ON u.id = r.owner_id
            """;

    private static final RowMapper<RoomRow> ROOM_MAPPER = (rs, i) -> new RoomRow(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getLong("owner_id"),
            rs.getString("nickname"),
            rs.getString("invite_code"),
            rs.getTimestamp("created_at").toInstant());

    private Optional<RoomRow> one(String where, Object key) {
        var rows = jdbc.query(
                ROOM_COLUMNS + where, new MapSqlParameterSource("key", key), ROOM_MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * @throws org.springframework.dao.DuplicateKeyException 초대 코드가 겹쳤을 때.
     *     32^8 중 하나라 사실상 일어나지 않지만, 일어났을 때 조용히 틀리는 것보다 터지는 편이 낫다.
     *     부르는 쪽이 다시 뽑아 재시도한다
     */
    UUID createRoom(String title, long ownerId, String inviteCode) {
        return jdbc.queryForObject(
                """
                INSERT INTO room (title, owner_id, invite_code)
                VALUES (:title, :owner, :code) RETURNING id
                """,
                new MapSqlParameterSource()
                        .addValue("title", title)
                        .addValue("owner", ownerId)
                        .addValue("code", inviteCode),
                UUID.class);
    }

    Optional<RoomRow> findRoom(UUID roomId) {
        return one("WHERE r.id = :key", roomId);
    }

    /** 코드로 방을 찾는다. 이미 정규화된(대문자·하이픈 없는) 코드가 들어온다. */
    Optional<RoomRow> findByCode(String inviteCode) {
        return one("WHERE r.invite_code = :key", inviteCode);
    }

    int updateCode(UUID roomId, String inviteCode) {
        return jdbc.update(
                "UPDATE room SET invite_code = :code WHERE id = :id",
                new MapSqlParameterSource().addValue("id", roomId).addValue("code", inviteCode));
    }

    /** 내가 참가한 방들. 최근 만든 것부터. */
    List<RoomRow> roomsOf(long userId) {
        return jdbc.query(
                ROOM_COLUMNS
                        + """
                        JOIN room_member m ON m.room_id = r.id AND m.user_id = :key
                        ORDER BY r.created_at DESC
                        """,
                new MapSqlParameterSource("key", userId),
                ROOM_MAPPER);
    }

    // ── 참가자 ──────────────────────────────────────────────────────────────

    /** 이미 들어가 있으면 아무 일도 하지 않는다. 재입장이 오류일 이유가 없다. */
    void join(UUID roomId, long userId) {
        jdbc.update(
                """
                INSERT INTO room_member (room_id, user_id) VALUES (:room, :uid)
                ON CONFLICT (room_id, user_id) DO NOTHING
                """,
                new MapSqlParameterSource().addValue("room", roomId).addValue("uid", userId));
    }

    boolean isMember(UUID roomId, long userId) {
        return !jdbc.queryForList(
                        "SELECT 1 FROM room_member WHERE room_id = :room AND user_id = :uid",
                        new MapSqlParameterSource().addValue("room", roomId).addValue("uid", userId),
                        Integer.class)
                .isEmpty();
    }

    Optional<Long> memberId(UUID roomId, long userId) {
        var rows = jdbc.queryForList(
                "SELECT id FROM room_member WHERE room_id = :room AND user_id = :uid",
                new MapSqlParameterSource().addValue("room", roomId).addValue("uid", userId),
                Long.class);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    List<RoomView.MemberView> members(UUID roomId, long viewerId, long activeBuildId) {
        var params = new MapSqlParameterSource()
                .addValue("room", roomId)
                .addValue("viewer", viewerId)
                .addValue("build", activeBuildId);
        return jdbc.query(
                """
                SELECT m.id, u.nickname, m.user_id, m.origin_label,
                       ST_X(ST_Transform(m.origin_geom, 4326)) AS lng,
                       ST_Y(ST_Transform(m.origin_geom, 4326)) AS lat,
                       m.origin_snap_m, m.origin_build_id, m.origin_geom IS NOT NULL AS origin_set
                FROM room_member m JOIN app_user u ON u.id = m.user_id
                WHERE m.room_id = :room
                ORDER BY m.joined_at, m.id
                """,
                params,
                (rs, i) -> {
                    boolean set = rs.getBoolean("origin_set");
                    long buildId = rs.getLong("origin_build_id");
                    return new RoomView.MemberView(
                            rs.getLong("id"),
                            rs.getString("nickname"),
                            rs.getLong("user_id") == viewerId,
                            set,
                            rs.getString("origin_label"),
                            set ? rs.getDouble("lng") : null,
                            set ? rs.getDouble("lat") : null,
                            set ? (Double) (double) rs.getFloat("origin_snap_m") : null,
                            set && buildId != activeBuildId);
                });
    }

    /**
     * 출발지를 저장한다.
     *
     * @param nodeId {@code graph_node.id}. 메모리 그래프의 첨자가 아니다 —
     *     첨자는 그래프를 다시 올릴 때마다 달라진다
     */
    void setOrigin(
            long memberId, String label, double lng, double lat,
            long nodeId, double snapM, long buildId) {
        var params = new MapSqlParameterSource()
                .addValue("id", memberId)
                .addValue("label", label)
                .addValue("lng", lng)
                .addValue("lat", lat)
                .addValue("node", nodeId)
                .addValue("snap", snapM)
                .addValue("build", buildId);
        jdbc.update(
                """
                UPDATE room_member SET
                    origin_label = :label,
                    origin_geom = ST_Transform(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), 5186),
                    origin_node_id = :node,
                    origin_snap_m = :snap,
                    origin_build_id = :build,
                    origin_set_at = now()
                WHERE id = :id
                """,
                params);
    }

    // ── 북마크 ──────────────────────────────────────────────────────────────

    /** {@code poi} 한 건의 이름·주소·좌표. 북마크에 복사해 둘 스냅샷이다. */
    record PoiSnapshot(String name, String address, double lng, double lat) {}

    Optional<PoiSnapshot> poiSnapshot(long poiId) {
        var rows = jdbc.query(
                """
                SELECT name, coalesce(road_address, jibun_address) AS address,
                       ST_X(ST_Transform(geom, 4326)) AS lng,
                       ST_Y(ST_Transform(geom, 4326)) AS lat
                FROM poi WHERE id = :id
                """,
                new MapSqlParameterSource("id", poiId),
                (rs, i) -> new PoiSnapshot(
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getDouble("lng"),
                        rs.getDouble("lat")));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    long addBookmark(
            UUID roomId, long userId, Long poiId,
            String name, String address, double lng, double lat) {
        var params = new MapSqlParameterSource()
                .addValue("room", roomId)
                .addValue("uid", userId)
                .addValue("poi", poiId)
                .addValue("name", name)
                .addValue("address", address)
                .addValue("lng", lng)
                .addValue("lat", lat);
        return jdbc.queryForObject(
                """
                INSERT INTO bookmark (room_id, added_by, poi_id, name, address, geom)
                VALUES (:room, :uid, :poi, :name, :address,
                        ST_Transform(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326), 5186))
                RETURNING id
                """,
                params,
                Long.class);
    }

    List<RoomView.BookmarkView> bookmarks(UUID roomId, long viewerId) {
        return jdbc.query(
                """
                SELECT b.id, b.poi_id, b.name, b.address, b.added_by, u.nickname, b.created_at,
                       ST_X(ST_Transform(b.geom, 4326)) AS lng,
                       ST_Y(ST_Transform(b.geom, 4326)) AS lat
                FROM bookmark b JOIN app_user u ON u.id = b.added_by
                WHERE b.room_id = :room
                ORDER BY b.created_at, b.id
                """,
                new MapSqlParameterSource().addValue("room", roomId),
                (rs, i) -> new RoomView.BookmarkView(
                        rs.getLong("id"),
                        rs.getObject("poi_id") == null ? null : rs.getLong("poi_id"),
                        rs.getString("name"),
                        rs.getString("address"),
                        rs.getDouble("lng"),
                        rs.getDouble("lat"),
                        rs.getString("nickname"),
                        rs.getLong("added_by") == viewerId,
                        rs.getTimestamp("created_at").toInstant()));
    }

    /** 담은 사람만 지울 수 있다. 지운 행 수를 돌려주므로 0 이면 권한이 없거나 없는 것이다. */
    int deleteBookmark(UUID roomId, long bookmarkId, long userId) {
        return jdbc.update(
                """
                DELETE FROM bookmark
                WHERE id = :id AND room_id = :room AND added_by = :uid
                """,
                new MapSqlParameterSource()
                        .addValue("id", bookmarkId)
                        .addValue("room", roomId)
                        .addValue("uid", userId));
    }

    /** 삭제가 0건일 때 "없는 것"과 "남의 것"을 가르기 위한 확인. */
    boolean bookmarkExists(UUID roomId, long bookmarkId) {
        return !jdbc.queryForList(
                        "SELECT 1 FROM bookmark WHERE id = :id AND room_id = :room",
                        new MapSqlParameterSource()
                                .addValue("id", bookmarkId)
                                .addValue("room", roomId),
                        Integer.class)
                .isEmpty();
    }

    boolean anyOriginSet(UUID roomId) {
        return !jdbc.queryForList(
                        "SELECT 1 FROM room_member WHERE room_id = :room AND origin_geom IS NOT NULL",
                        new MapSqlParameterSource("room", roomId),
                        Integer.class)
                .isEmpty();
    }
}
