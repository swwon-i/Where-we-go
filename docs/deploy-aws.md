# AWS(EC2 한 대)에 올리기

EC2 한 대에 지금 Docker Compose 를 그대로 올린다. 앞에 Caddy 가 HTTPS 를 맡고,
매일 22:00 cron 이 인허가 원본을 받아 적재한다.

```
인터넷 ──443──▶ Caddy ──▶ server:8080 ──▶ db:5432
                (인증서 자동)   (밖에 안 열림)   (127.0.0.1 만)
cron 22:00 ──▶ etl 컨테이너: fetch → ingest
```

처음이면 반나절, 코드는 준비돼 있으니 대부분 AWS 콘솔과 명령어 붙여 넣기다.

## 0. 사양

| | 값 | 이유 |
|---|---|---|
| 인스턴스 | **t3.small** (2 vCPU · 2GB) + 스왑 2GB | 평소 DB ~0.3GB + 서버 ~0.4GB. 적재가 돌 때 ETL 이 0.28GB 더 쓴다 (잰 값, 나눠 읽기 전에는 1.4GB 였다) |
| 디스크 | gp3 **30GB** | DB 약 2.3GB. raw 는 최근 7회차만, 검수 기록은 30회차만 남는다 |
| OS | Ubuntu 24.04 LTS | |
| 리전 | 서울 (ap-northeast-2) | |

서버에서 **이미지를 빌드할 때**(Gradle·npm) 메모리가 가장 많이 든다. 스왑을 먼저 잡는다.

## 1. EC2 만들기 (콘솔)

1. EC2 → 인스턴스 시작 — Ubuntu 24.04, t3.small, 스토리지 30GB gp3, 키 페어 새로 만들기(.pem 보관)
2. 보안 그룹 인바운드
   | 포트 | 소스 |
   |---|---|
   | 22 (SSH) | **내 IP** 만 |
   | 80 (HTTP) | 0.0.0.0/0 — 인증서 발급과 https 로 넘기기 |
   | 443 (HTTPS) | 0.0.0.0/0 |

   5432 와 8080 은 **열지 않는다.**
3. 탄력적 IP 를 하나 받아 인스턴스에 연결한다. 재시작해도 주소가 바뀌지 않는다

## 2. 도메인

- 도메인이 있으면 A 레코드를 탄력적 IP 로
- 없으면 **sslip.io** — IP `3.35.1.2` 라면 `3-35-1-2.sslip.io` 가 그 IP 를 가리킨다. 가입 없이 HTTPS 인증서도 받는다

카카오 개발자 콘솔 → 내 애플리케이션 → **플랫폼 > Web** 에 `https://도메인` 을 추가한다. 빠뜨리면 지도만 안 뜬다.

## 3. 서버 준비 (SSH)

```bash
ssh -i where-we-go.pem ubuntu@<탄력적 IP>

# 시간대 — cron 의 22:00 이 한국 시각이 되도록
sudo timedatectl set-timezone Asia/Seoul

# 스왑 2GB
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# Docker (공식 저장소). compose 는 v2.24 이상이어야 한다 — docker-compose.prod.yml 이 !reset 을 쓴다
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu && exit       # 다시 접속해야 그룹이 먹는다
```

## 4. 코드와 설정

```bash
ssh -i where-we-go.pem ubuntu@<탄력적 IP>
git clone https://github.com/swwon-i/Where-we-go.git && cd Where-we-go
nano .env
```

`.env` (저장소 루트, git 에 들어가지 않는다):

```bash
# 이 두 파일을 합쳐 쓴다 — 모든 docker compose 명령과 일일 실행이 따른다
COMPOSE_FILE=docker-compose.yml:docker-compose.prod.yml

WWG_DOMAIN=3-35-1-2.sslip.io
WWG_DB_PASSWORD=<길고 무작위인 값>
WWG_KAKAO_JS_KEY=<카카오 JavaScript 키>
WWG_ADMIN_LOGIN_IDS=<관리자 아이디>
```

`WWG_DB_PASSWORD` 는 **DB 를 처음 만들 때만** 들어간다. 나중에 바꾸려면 DB 안에서 `ALTER USER` 로 바꾸고 `.env` 도 맞춘다.

## 5. 데이터 옮기기

그래프 빌드(OSM 보행망, 시각표, 버스)는 원본 340MB 와 OSMnx 가 필요해 서버에서 하지 않는다.
**로컬 DB 를 통째로 덤프해 옮긴다.** raw 는 다시 만들 수 있으니 빼고 뜬다.

로컬(Windows, Git Bash):

```bash
export MSYS_NO_PATHCONV=1          # Git Bash 가 컨테이너 안 경로를 윈도우 경로로 바꾸지 않게
docker exec wwg-db sh -c 'pg_dump -U "$POSTGRES_USER" -d wherewego -Fc --exclude-table-data=poi_raw -f /tmp/wwg.dump'
docker cp wwg-db:/tmp/wwg.dump ./wwg.dump                          # 약 170MB
scp -i where-we-go.pem ./wwg.dump ubuntu@<탄력적 IP>:~/
```

서버:

```bash
cd ~/Where-we-go
docker compose up -d db                                   # 빈 DB (PostGIS 포함)
docker compose cp ~/wwg.dump db:/tmp/wwg.dump
docker compose exec db pg_restore -U wherewego -d wherewego --no-owner /tmp/wwg.dump    # 약 1분
docker compose exec db psql -U wherewego -d wherewego -c "select count(*) from poi"     # 646,848 근처
rm ~/wwg.dump
```

(로컬에서 빈 PostGIS 컨테이너로 같은 복원을 해 봤다 — 오류 없이 52초, POI · 그래프 · 마이그레이션 V11 · staging 전부 들어왔다.)

마이그레이션 기록(`flyway_schema_history`)도 같이 오므로 Flyway 를 다시 돌릴 필요가 없다.
나중에 새 마이그레이션이 생기면 `docker compose --profile migrate run --rm flyway`.

## 6. 띄우기

```bash
docker compose up -d --build        # 처음엔 이미지 빌드로 10분 안팎
docker compose ps                   # db · server · caddy 가 Up
docker compose logs -f caddy        # "certificate obtained successfully" 가 보이면 HTTPS 준비 끝
```

`https://도메인` 을 연다. 지도 · 장소 검색 · 가입 · 방 · 행렬을 차례로 확인한다.

## 7. 매일 22:00 갱신

```bash
docker compose --profile etl build etl
etl/run_daily.sh && tail -30 etl/out/logs/ingest-$(date +%Y%m%d).log     # 한 번 손으로
etl/install_cron.sh                                                       # cron 등록 (22:00)
```

처음 손으로 돌리면 원본 두 파일(약 209MB)을 받는데, 서버에는 `csv/` 가 비어 있으므로
"기존 없음 → 새 파일" 로 받고, 적재는 로컬에서 옮겨 온 회차와 비교해 신규·폐업 전환을 센다.

## 운영

| | |
|---|---|
| 코드 갱신 | `git pull && docker compose up -d --build` |
| 로그 | `docker compose logs --tail 100 server` · 적재는 `etl/out/logs/` |
| 적재 기록 | 관리자 화면 `/#/admin` 또는 `ingest_run` 테이블 |
| 재부팅 | db · server · caddy 는 `restart: unless-stopped` 라 저절로 뜬다. cron 도 그대로 |
| 백업 | 5번의 `pg_dump` 를 cron 에 걸거나 EBS 스냅샷 |
| 비용 | t3.small 서울 기준 월 $20 안팎 + 디스크·공인 IPv4 몇 달러. **안 쓸 때는 인스턴스를 중지**하면 컴퓨팅 요금이 멈춘다 (디스크·탄력적 IP 는 계속 과금) |

## 공개 서버에서 달라지는 것

| | 로컬 | 서버 (`docker-compose.prod.yml`) |
|---|---|---|
| DB 포트 | `127.0.0.1:5432` | 같음 — 밖에서 닿지 않는다 |
| 서버 포트 | `8080` | 닫힘. Caddy 443 으로만 |
| 로그인 쿠키 | http 허용 | **HTTPS 전용** (`WWG_COOKIE_SECURE`) |
| 횟수 제한 | 꺼짐 | **켜짐** (`WWG_RATE_LIMIT`) — IP 당 가입 5/시간 · 로그인 20/10분 · 방 만들기 30/시간 · 행렬 60/10분 |
| 접속자 IP | 그대로 | Caddy 가 붙인 `X-Forwarded-For` 를 믿는다 (서버 포트가 닫혀 있어 Caddy 만 붙일 수 있다) |
