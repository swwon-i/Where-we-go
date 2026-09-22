package com.wherewego.account;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 누가 관리자인가.
 *
 * <p><b>설정에 적힌 로그인 아이디만 관리자다</b>({@code WWG_ADMIN_LOGIN_IDS}, 쉼표로 구분).
 * DB 에 역할 컬럼을 두지 않는다 — 가입 화면이나 API 로 스스로 관리자가 될 길이 원천적으로 없고,
 * 설정과 DB 가 어긋날 일도 없다. 관리자가 한두 명인 서비스에서는 이것으로 충분하다.
 *
 * <p>로그인할 때 권한을 세션에 굳히지 않고 <b>요청마다</b> 이 목록을 본다. 목록에서 빼면
 * 서버를 다시 띄운 뒤 첫 요청부터 막힌다 — 이미 로그인해 있던 세션도 마찬가지다.
 */
@Component
public class AdminPolicy {

    private static final Logger log = LoggerFactory.getLogger(AdminPolicy.class);

    private final Set<String> loginIds;

    public AdminPolicy(@Value("${wwg.admin-login-ids:}") String configured) {
        // 로그인 아이디는 소문자로 저장된다(SignupRequest). 설정도 같은 규칙으로 맞춘다.
        this.loginIds = Arrays.stream(configured.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        // 비어 있으면 /admin 을 아무도 못 연다. "왜 403 인가"를 로그 한 줄로 알 수 있게 한다.
        if (loginIds.isEmpty()) {
            log.info("관리자가 지정되지 않았다 — WWG_ADMIN_LOGIN_IDS 가 비어 있어 /admin 은 모두 403");
        } else {
            log.info("관리자 {}명 지정됨", loginIds.size());
        }
    }

    public boolean isAdmin(String loginId) {
        return loginId != null && loginIds.contains(loginId.toLowerCase(Locale.ROOT));
    }

    /** 보안 설정이 요청마다 부른다. 로그인하지 않았으면 거짓 — 그러면 401 이 나간다. */
    public boolean isAdmin(Authentication auth) {
        return auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)
                && isAdmin(auth.getName());
    }
}
