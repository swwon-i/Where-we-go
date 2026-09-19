package com.wherewego.account;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입과 조회.
 *
 * <p>로그인 자체는 여기서 하지 않는다. Spring Security 의 {@code AuthenticationManager} 가
 * 이 클래스를 {@link UserDetailsService} 로 불러 해시를 대조한다 — 비밀번호를 비교하는 코드를
 * 직접 쓰지 않는다는 뜻이고, 그게 낫다. 타이밍 공격 방어나 인코더 교체 같은 것이 딸려 온다.
 */
@Service
public class AccountService implements UserDetailsService {

    private final AccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AccountService(AccountRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /** 이미 쓰이는 아이디·닉네임. 어느 칸이 문제인지 화면에 표시하기 위한 것이다. */
    public static class DuplicateException extends RuntimeException {
        private final String field;

        DuplicateException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /**
     * 가입.
     *
     * @return 만들어진 계정의 id
     */
    @Transactional
    public long signup(SignupRequest request) {
        String loginId = request.normalizedLoginId();
        String nickname = request.normalizedNickname();

        // 먼저 확인하는 것은 **어느 칸이 문제인지 알려주기 위해서**다.
        // 중복을 실제로 막는 것은 DB 의 제약이다 — 확인과 INSERT 사이에 다른 요청이 끼어들 수 있다.
        if (repository.loginIdTaken(loginId)) {
            throw new DuplicateException("loginId", "이미 쓰이는 아이디입니다");
        }
        if (repository.nicknameTaken(nickname)) {
            throw new DuplicateException("nickname", "이미 쓰이는 닉네임입니다");
        }

        try {
            return repository.insert(loginId, nickname, passwordEncoder.encode(request.password()));
        } catch (DuplicateKeyException e) {
            // 확인을 통과했는데도 걸렸다면 그 사이에 같은 값이 들어온 것이다.
            // 어느 칸인지 다시 따지지 않는다 — 둘 중 하나이고, 재시도하면 위에서 걸린다.
            throw new DuplicateException("loginId", "이미 쓰이는 아이디나 닉네임입니다");
        }
    }

    AppUser byLoginId(String loginId) {
        return repository
                .findByLoginId(loginId == null ? "" : loginId.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("없는 계정"));
    }

    AppUser byId(long id) {
        return repository.findById(id).orElseThrow(() -> new UsernameNotFoundException("없는 계정"));
    }

    /**
     * Spring Security 가 로그인할 때 부른다.
     *
     * <p>{@code username} 자리에 로그인 아이디가 온다. 입력이 대문자로 와도 찾을 수 있도록
     * 여기서도 소문자로 맞춘다 — 저장은 이미 소문자로 되어 있다.
     */
    @Override
    public UserDetails loadUserByUsername(String loginId) {
        var user = repository
                .findByLoginId(loginId == null ? "" : loginId.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("아이디나 비밀번호가 올바르지 않습니다"));

        return User.withUsername(user.loginId())
                .password(user.passwordHash())
                .authorities("ROLE_USER")
                .build();
    }
}
