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

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "http://localhost:8080")
                .allowedMethods("GET", "POST", "PUT", "DELETE");
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
