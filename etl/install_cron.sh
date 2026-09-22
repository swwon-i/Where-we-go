#!/bin/sh
# 일일 갱신을 cron 에 등록한다. 서버(Linux)에서 한 번 돌린다. 다시 돌려도 한 줄만 남는다.
#
#   etl/install_cron.sh          매일 22:00 (서버 시간대 기준)
#   etl/install_cron.sh 30 21    매일 21:30
#
# **시간대를 먼저 맞춘다.** EC2 는 UTC 라 그대로 두면 22:00 이 한국 시각 오전 7시다.
#   sudo timedatectl set-timezone Asia/Seoul
set -eu

MIN=${1:-0}
HOUR=${2:-22}
REPO=$(cd "$(dirname "$0")/.." && pwd)
SCRIPT="$REPO/etl/run_daily.sh"
MARK="# where-we-go daily ingest"

chmod +x "$SCRIPT"

TZ_NOW=$(date +%Z)
if [ "$TZ_NOW" = "UTC" ]; then
    echo "서버 시간대가 UTC 다. ${HOUR}:$(printf %02d "$MIN") 은 한국 시각이 아니다."
    echo "  sudo timedatectl set-timezone Asia/Seoul   후 다시 실행하거나, 시각을 UTC 로 넘겨라."
fi

# 기존 줄을 지우고 새로 넣는다 — 여러 번 돌려도 중복되지 않는다.
{ crontab -l 2>/dev/null | grep -v "$MARK" || true; echo "$MIN $HOUR * * * $SCRIPT $MARK"; } | crontab -

echo "등록했다:"
crontab -l | grep "$MARK"
