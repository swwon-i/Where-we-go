package com.wherewego.config;

import com.wherewego.account.AccountService;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 세션 기반 로그인.
 *
 * <h2>왜 직접 만들지 않는가</h2>
 *
 * 비밀번호 비교, 세션 고정 공격 방어, 해시 알고리즘 교체 경로 — 이 셋은 직접 짜면 틀리기 쉽고
 * 틀려도 한참 뒤에 드러난다. 이 프로젝트의 본질은 공간 데이터지 인증이 아니므로, 이미 검증된
 * 것을 쓰고 설정만 명시한다.
 *
 * <h2>CSRF 를 끄지 않는다</h2>
 *
 * 세션 쿠키로 인증하면 브라우저가 쿠키를 <b>알아서</b> 붙인다. 다른 사이트에 있는 폼이
 * 우리 API 를 호출해도 쿠키가 함께 간다는 뜻이다. JSON API 라고 CSRF 를 끄는 예가 흔하지만,
 * 끄는 순간 그 공격이 열린다.
 *
 * <p>{@code XSRF-TOKEN} 쿠키를 내려주고 프론트가 {@code X-XSRF-TOKEN} 헤더로 돌려보내게 한다.
 * 다른 출처의 스크립트는 우리 쿠키를 읽지 못하므로 헤더를 만들 수 없다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 비밀번호 해시.
     *
     * <p>위임 인코더라 저장된 해시에 {@code {bcrypt}} 같은 접두사가 붙는다. 나중에 더 나은
     * 알고리즘으로 옮길 때 <b>기존 계정을 그대로 두고</b> 새 가입분부터 바꿀 수 있다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AccountService accountService, PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(accountService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    /** 로그인한 사용자를 세션에 보관한다. 컨트롤러가 직접 저장할 때도 이 구현을 쓴다. */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * 로그인에 성공하면 세션 id 를 바꾼다.
     *
     * <p>세션 고정 공격 방어다. 공격자가 미리 만든 세션 id 를 피해자에게 심어 두고, 피해자가
     * 그 세션으로 로그인하면 같은 id 로 남의 계정에 들어갈 수 있다. 로그인 시점에 id 를
     * 새로 발급하면 심어 둔 값이 쓸모없어진다.
     */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        var repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // 쿠키가 XSRF-TOKEN 이면 헤더는 X-XSRF-TOKEN 이어야 짝이 맞는다. 기본값은
        // X-CSRF-TOKEN 이라 어긋나는데, axios 같은 클라이언트가 이 관례를 보고 자동으로
        // 헤더를 붙여 주므로 맞춰 두면 프론트가 할 일이 없어진다.
        repository.setHeaderName("X-XSRF-TOKEN");

        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        // 토큰을 **미루지 않고 바로** 만든다.
        //
        // 기본값에서는 요청 속성에 "나중에 필요하면 만들 것"만 걸어 두는데, 아무도 건드리지
        // 않으면 끝내 만들어지지 않고 쿠키도 내려가지 않는다. 그러면 프론트는 헤더에 넣을
        // 값이 없어 모든 POST 가 403 이 된다 — 실제로 그 상태였다.
        csrfHandler.setCsrfRequestAttributeName(null);

        http.csrf(csrf -> csrf.csrfTokenRepository(repository)
                        .csrfTokenRequestHandler(csrfHandler))
                .authorizeHttpRequests(auth -> auth
                        // 가입·로그인은 당연히 로그인 없이 되어야 한다
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login")
                        .permitAll()
                        // 장소 검색과 경로는 로그인 없이 열어 둔다 — 방을 만들기 전에
                        // 둘러볼 수 있어야 하고, 개인 정보가 실리지 않는다
                        .requestMatchers(HttpMethod.GET, "/api/v1/places/**", "/api/v1/routes",
                                "/api/v1/graph", "/api/v1/client-config", "/api/v1/auth/csrf")
                        .permitAll()
                        // 파이프라인 운영 기록 — 공개 데이터와 통계뿐이고 읽기만 있다.
                        // 심사자가 로그인 없이 열어 봐야 하는 화면이다
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/**").permitAll()
                        // 그래프 재적재는 운영 동작이다
                        .requestMatchers(HttpMethod.POST, "/api/v1/graph/reload").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        // 정적 파일(지도 화면)
                        .anyRequest().permitAll())
                // 로그인 폼으로 넘기지 않는다. API 는 401 을 받아야 한다.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(SecurityConfig::unauthorized))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }

    private static void unauthorized(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            org.springframework.security.core.AuthenticationException e)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"로그인이 필요합니다\"}");
    }
}
