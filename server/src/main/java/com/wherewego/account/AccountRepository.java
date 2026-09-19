package com.wherewego.account;

import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 계정 조회·저장. */
@Repository
public class AccountRepository {

    private static final String COLUMNS =
            "SELECT id, login_id, nickname, password_hash, created_at FROM app_user ";

    private static final RowMapper<AppUser> MAPPER = (rs, i) -> new AppUser(
            rs.getLong("id"),
            rs.getString("login_id"),
            rs.getString("nickname"),
            rs.getString("password_hash"),
            rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public AccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AppUser> findByLoginId(String loginId) {
        var rows = jdbc.query(
                COLUMNS + "WHERE login_id = :loginId",
                new MapSqlParameterSource("loginId", loginId),
                MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    Optional<AppUser> findById(long id) {
        var rows = jdbc.query(
                COLUMNS + "WHERE id = :id", new MapSqlParameterSource("id", id), MAPPER);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    boolean loginIdTaken(String loginId) {
        return exists("SELECT 1 FROM app_user WHERE login_id = :v", loginId);
    }

    /** 대소문자를 무시하고 본다. {@code uq_app_user_nickname} 과 같은 기준이어야 한다. */
    boolean nicknameTaken(String nickname) {
        return exists("SELECT 1 FROM app_user WHERE lower(nickname) = lower(:v)", nickname);
    }

    private boolean exists(String sql, String value) {
        return !jdbc.queryForList(sql, new MapSqlParameterSource("v", value), Integer.class)
                .isEmpty();
    }

    /**
     * 계정을 만든다.
     *
     * @throws org.springframework.dao.DuplicateKeyException 중복 제약에 걸렸을 때.
     *     미리 확인해도 그 사이에 다른 요청이 같은 값을 넣을 수 있으므로 <b>이 예외가
     *     최종 방어선</b>이다. 확인만 믿으면 경쟁 조건에서 뚫린다
     */
    long insert(String loginId, String nickname, String passwordHash) {
        var params = new MapSqlParameterSource()
                .addValue("loginId", loginId)
                .addValue("nickname", nickname)
                .addValue("hash", passwordHash);
        return jdbc.queryForObject(
                """
                INSERT INTO app_user (login_id, nickname, password_hash)
                VALUES (:loginId, :nickname, :hash)
                RETURNING id
                """,
                params,
                Long.class);
    }
}
