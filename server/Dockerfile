# 빌드 컨텍스트는 저장소 루트다 (web/ 과 server/ 를 모두 봐야 한다).
#   docker compose build server

# ── 1. 프론트 빌드 ────────────────────────────────────────────────────────────
# 결과를 서버의 정적 폴더로 넣어 jar 하나로 배포한다.
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY web/ ./
RUN npx vite build --outDir /web-dist --emptyOutDir

# ── 2. 서버 빌드 ──────────────────────────────────────────────────────────────
# wrapper 가 저장소에 있으므로 Gradle 을 따로 설치하지 않는다.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# 의존성 레이어를 소스와 분리해 캐시가 살아 있게 한다.
COPY server/gradlew server/settings.gradle server/build.gradle ./
COPY server/gradle ./gradle
# 실행 권한은 git 에 기록돼 있지만(100755), 압축본으로 받거나 권한을 잃은 체크아웃에서도 돌게 한다.
# 리눅스에서 이것 없이 빌드하다 "./gradlew: Permission denied" (exit 126) 로 죽었다.
RUN chmod +x gradlew
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY server/src ./src
COPY --from=web /web-dist ./src/main/resources/static
RUN ./gradlew --no-daemon bootJar

# ── 3. 실행 ───────────────────────────────────────────────────────────────────
# JDK 가 아니라 JRE 로 띄운다.
FROM eclipse-temurin:25-jre
WORKDIR /app

# root 로 돌리지 않는다.
RUN useradd --system --create-home --uid 10001 wwg
USER wwg

COPY --from=build --chown=wwg:wwg /src/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
