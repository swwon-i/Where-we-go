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

    /**
     * 로그인·세션 확인에 쓴다. <b>탈퇴한 계정은 없는 것으로 본다</b> —
     * 비밀번호가 맞을 수 없게 해 두었지만, 비교까지 가지 않는 편이 확실하다.
     */
    Optional<AppUser> findByLoginId(String loginId) {
        var rows = jdbc.query(
                COLUMNS + "WHERE login_id = :loginId AND deleted_at IS NULL",
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

    /**
     * 대소문자를 무시하고 본다.
     *
     * <p>인덱스와 달리 <b>탈퇴한 계정까지 본다</b>. 인덱스는 살아 있는 계정에만 걸려 있어
     * 탈퇴자끼리는 이름이 겹쳐도 되지만, 여기서까지 빼면 새로 가입하는 사람이
     * '탈퇴한 사용자'를 이름으로 가져갈 수 있다. 남의 탈퇴를 가장하는 이름이 된다.
     */
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

    /**
     * 탈퇴. 행을 지우지 않고 식별자만 지운다.
     *
     * <p>행을 지우면 이 사람이 만든 방과 담은 후보가 가리킬 곳을 잃는다. 그것들은 혼자
     * 만든 것이 아니므로 남겨야 하고, 그래서 사람만 지운다.
     *
     * <p>로그인 아이디는 {@code deleted_<id>} 가 된다. 다시 들어올 길이 없어지고,
     * 쓰던 아이디는 풀려 다른 사람이 가져갈 수 있다. 비밀번호 자리에는 BCrypt 모양이
     * 아닌 값을 넣어 어떤 입력과도 맞지 않게 한다 — {@code {noop\}} 로 두면 빈 비밀번호와
     * 맞아 버린다.
     *
     * @return 바뀐 행 수. 이미 탈퇴한 계정이면 0
     */
    int anonymize(long id) {
        return jdbc.update(
                """
                UPDATE app_user SET
                    login_id = 'deleted_' || id,
                    nickname = '탈퇴한 사용자',
                    password_hash = '{bcrypt}deleted',
                    deleted_at = now()
                WHERE id = :id AND deleted_at IS NULL
                """,
                new MapSqlParameterSource("id", id));
    }
}
