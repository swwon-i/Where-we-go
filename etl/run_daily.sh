#!/bin/sh
# 일일 갱신 — 서버(Linux)의 cron 이 부른다. Windows 노트북은 run_daily.cmd 를 쓴다.
#
#   받기(etl.fetch) → 적재(etl.ingest)
#
# 둘 다 etl 컨테이너에서 돈다. 호스트에 파이썬이 필요 없다.
# 등록: etl/install_cron.sh     수동: etl/run_daily.sh
#
# 종료 코드는 run_daily.cmd 와 같다.
#   0  성공 또는 SKIPPED (원본이 어제와 같음)
#   1  준비 문제 (docker 없음 등)
#   4  받기 실패 — 기존 파일로 적재했다
#   그 밖  적재 실패
set -u

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO" || exit 1

LOGDIR="$REPO/etl/out/logs"
mkdir -p "$LOGDIR"
# 볼륨으로 붙일 폴더를 먼저 만든다. 없으면 docker 가 root 소유로 만들어 컨테이너(uid 1000)가
# 받은 파일을 쓰지 못한다. csv/ 는 git 에 없어 새로 클론한 서버에는 아직 없다.
mkdir -p "$REPO/csv/장소"
LOG="$LOGDIR/ingest-$(date +%Y%m%d).log"

# cron 은 PATH 가 짧다. docker 가 /usr/bin 밖에 있으면 여기서 못 찾는다.
PATH="/usr/local/bin:/usr/bin:/bin:$PATH"
if ! command -v docker >/dev/null 2>&1; then
    echo "docker not found" >> "$LOG"
    exit 1
fi

{
    echo
    echo "==== $(date '+%Y-%m-%d %H:%M:%S %Z') ===="
} >> "$LOG"

# .env 의 COMPOSE_FILE 을 compose 가 읽으므로 서버에서는 prod 설정이 자동으로 붙는다.
# --user: 이미지의 기본 사용자는 uid 1000 인데 서버 계정이 1000 이 아닐 수 있다(GCP 가 그렇다).
# 그대로 두면 받은 파일을 csv/ 에 쓰지 못한다 — Permission denied 로 죽었다.
run_etl() {
    docker compose --profile etl run --rm -T --user "$(id -u):$(id -g)" etl "$@" >> "$LOG" 2>&1
}

run_etl etl.fetch --source all
FETCH=$?

# --wait-db 180: 서버가 막 재부팅된 직후라면 DB 가 아직 뜨는 중일 수 있다.
run_etl etl.ingest --source all --wait-db 180
CODE=$?

# 3 = 기다려도 DB 에 닿지 못했다. 적재 실패가 아니라 DB 가 꺼져 있던 것이다.
if [ "$CODE" -eq 3 ]; then
    echo "database not reachable - skipped" >> "$LOG"
    CODE=0
fi

if [ "$CODE" -eq 0 ] && [ "$FETCH" -ne 0 ]; then
    echo "fetch failed with $FETCH - ingested the existing files" >> "$LOG"
    CODE=$FETCH
fi

echo "exit code $CODE" >> "$LOG"

# 오래된 로그는 90일 뒤 지운다. 기록의 원본은 DB 의 ingest_run 이다.
find "$LOGDIR" -name 'ingest-*.log' -mtime +90 -delete 2>/dev/null

exit "$CODE"
