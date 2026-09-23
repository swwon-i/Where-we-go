package com.wherewego.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 개발용 설정. 프론트를 Vite 로 분리하면 CORS 가 필요해진다. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 다른 출처에서 **세션 쿠키를 붙여** API 를 부를 수 있는 곳. 기본은 Vite 개발 서버다.
     *
     * <p>운영에서는 비운다(docker-compose.prod.yml) — 서버가 화면을 같이 내려주므로 CORS 가
     * 필요 없고, 목록에 남은 출처는 "그 주소에서 뜬 페이지가 로그인한 사용자의 자격으로 우리 API 를
     * 부를 수 있다"는 뜻이다. 빈 값이면 매핑을 만들지 않는다.
     */
    @Value("${wwg.cors-origins:http://localhost:5173,http://localhost:8080}")
    private String corsOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = java.util.Arrays.stream(corsOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        if (origins.length == 0) return;
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                // 세션 쿠키를 주고받아야 하므로 자격 증명을 허용한다.
                // 이것을 켜면 allowedOrigins 에 "*" 를 쓸 수 없다 — 출처를 못박아야 한다.
                .allowCredentials(true);
    }

    /**
     * 카카오맵 JavaScript 키를 프론트에 넘긴다.
     *
     * <p>키를 HTML 에 박아 커밋하지 않기 위한 우회로다. 값은 환경변수나 {@code .env} 에서 오고,
     * 저장소에는 들어가지 않는다. 키가 없으면 빈 문자열이 가고 프론트가 안내 문구를 띄운다.
     */
    @RestController
    static class ClientConfigController {

        private final String kakaoJsKey;

        ClientConfigController(@Value("${wwg.kakao-js-key:}") String kakaoJsKey) {
            this.kakaoJsKey = kakaoJsKey;
        }

        @GetMapping("/api/v1/client-config")
        public ClientConfig get() {
            return new ClientConfig(kakaoJsKey);
        }

        record ClientConfig(String kakaoJsKey) {}
    }
}
