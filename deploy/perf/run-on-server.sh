#!/usr/bin/env bash
# 운영 서버(GCP e2-small · vCPU 2 · 메모리 2GB)에서 성능 측정을 돌린다.
#
#   cd ~/Where-we-go && git pull
#   tmux new -s perf                      # ssh 가 끊겨도 계속 돌게
#   deploy/perf/run-on-server.sh --pgrouting --twice
#
# ── 서비스가 멈춘다 ─────────────────────────────────────────────────────────
#   측정하는 동안 서버 컨테이너를 내린다. 측정 JVM 과 운영 서버 JVM 이 2GB 를 나눠 쓰면
#   스왑으로 밀려서 측정이 오염되기 때문이다. 내리면 "VM 하나에 앱 하나 + DB" 가 되고, 그게
#   지금 운영 구조 그대로다 — 측정 JVM 이 앱 자리에 선다.
#   사용자가 있으면 쓰는 사람이 없는 시간(새벽)에 돌린다. 시작 전에 최근 30분 동안의 행렬
#   계산 수를 보여준다 — 0 이 아니면 누가 쓰고 있다는 뜻이다. 짧게 끝내려면 --only-pgrouting.
#
# ── 무엇을 하나 ─────────────────────────────────────────────────────────────
#   1. 확인: 저장소에 측정 코드가 있는지, DB 가 떴는지, 활성 그래프가 있는지, ETL(22:00) 시간이 아닌지
#   2. JDK 25 가 없으면 ~/.jdks 에 받는다 (Eclipse Temurin)
#   3. 서버 컨테이너를 내린다 — 끝나면(실패해도) 다시 띄운다
#   4. 측정하는 내내 기록한다
#        vmstat.log  CPU(us·sy·st)·스왑(si·so) 5초마다. st(steal) 가 공유 코어의 버스트 제한을 보여준다
#        dbmem.log   DB 컨테이너 메모리 5초마다. pgRouting 이 DB 안에서 쓰는 메모리 — 로컬 측정의 빈칸
#   5. 측정: 알고리즘 대조 → 동시 요청 스윕(→ --twice 면 한 번 더) → (--pgrouting 이면) pgRouting 대조
#   6. 정리: 측정용 표·확장이 남았으면 지우고 서버를 다시 띄운다
#   7. 결과를 perf-results/server-<시각>.tar.gz 로 묶는다
#
# ── 옵션 ────────────────────────────────────────────────────────────────────
#   --pgrouting   pgRouting 을 DB 컨테이너에 넣고 대조 측정까지 (컨테이너를 다시 만들면 사라진다)
#   --only-pgrouting  pgRouting 대조만 돈다 (알고리즘·스윕은 건너뛴다). 서버가 멈추는 시간이
#                 15분 안쪽으로 줄어든다. 동시 수준은 코어 수까지만 오른다(vCPU 2 면 1·2)
#   --twice       동시 요청 스윕을 연달아 한 번 더. e2-small 은 공유 코어라 부하가 오래가면
#                 버스트가 줄어든다 — 1회차(쉬던 상태)와 2회차(오래 달린 뒤)의 차이가 그 크기다
#   --yes         확인 질문 없이 진행
#   --dry-run     확인만 하고 아무것도 바꾸지 않는다
#
# 오래 걸린다. 코어가 2 개라 개발 머신(16 코어)에서 15분 걸리던 것이 30분~1시간 걸릴 수 있다.

set -euo pipefail

WITH_PGR=0
ONLY_PGR=0
TWICE=0
ASSUME_YES=0
DRY_RUN=0
for arg in "$@"; do
    case "$arg" in
        --pgrouting) WITH_PGR=1 ;;
        --only-pgrouting) WITH_PGR=1; ONLY_PGR=1 ;;
        --twice) TWICE=1 ;;
        --yes) ASSUME_YES=1 ;;
        --dry-run) DRY_RUN=1 ;;
        -h|--help) sed -n '2,40p' "$0"; exit 0 ;;
        *) echo "모르는 옵션: $arg (--help)" >&2; exit 2 ;;
    esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# 측정 JVM 의 힙. 512m 는 운영 서버가 -Xmx 없이 뜰 때 JVM 이 잡는 값(RAM 2GB 의 1/4)과 같다 —
# 스윕은 운영과 같은 조건으로 잰다. 알고리즘·pgRouting 대조는 힙이 결과에 거의 안 걸리므로
# 넉넉히 주되 2GB 서버에 들어가게 768m.
SWEEP_HEAP=512m
PERF_HEAP=768m
# Gradle 자신의 JVM. 컴파일이 돌 만큼만.
GRADLE_JVM=-Xmx512m

STAMP="$(TZ=Asia/Seoul date +%Y%m%d-%H%M)"
OUT="$ROOT/perf-results/server-$STAMP"
DB=wwg-db

say() { echo "[$(TZ=Asia/Seoul date +%H:%M:%S)] $*"; }
die() { echo "✗ $*" >&2; exit 1; }
psql_q() { docker exec "$DB" psql -U wherewego -d wherewego -tAc "$1"; }

# ── 1. 확인 ─────────────────────────────────────────────────────────────────

[ -f .env ] || die ".env 가 없다. 저장소 루트($ROOT)에서 돌려야 한다"
[ -f server/src/test/java/com/wherewego/routing/ConcurrencySweepTest.java ] \
    || die "측정 코드가 없다. git pull 먼저"
docker ps --format '{{.Names}}' | grep -qx "$DB" || die "$DB 컨테이너가 떠 있지 않다"

ACTIVE_BUILD="$(psql_q "SELECT id FROM graph_build WHERE is_active" || true)"
[ -n "$ACTIVE_BUILD" ] || die "활성 그래프 빌드가 없다"

# ETL 은 22:00 에 돌고 적재가 수 분 걸린다. 그 시간대에 측정하면 CPU·DB 를 같이 쓴다.
NOW_HM=$((10#$(TZ=Asia/Seoul date +%H%M)))
if [ "$NOW_HM" -ge 2130 ] && [ "$NOW_HM" -le 2300 ]; then
    die "21:30~23:00 은 ETL(22:00) 시간이다. 그 뒤에 돌린다"
fi

FREE_GB=$(df -BG --output=avail "$ROOT" | tail -1 | tr -dc '0-9')
[ "${FREE_GB:-0}" -ge 2 ] || die "디스크 여유가 ${FREE_GB}GB 뿐이다 (JDK·Gradle 에 2GB 쯤 든다)"

command -v vmstat >/dev/null || die "vmstat 이 없다 (sudo apt install procps)"

PGR_BEFORE="$(psql_q "SELECT count(*) FROM pg_extension WHERE extname='pgrouting'")"

# 지금 누가 쓰고 있는지. 행렬 계산 기록은 위치 없이 숫자만 남는다(matrix_run).
RECENT_RUNS="$(psql_q "SELECT count(*) FROM matrix_run WHERE created_at > now() - interval '30 minutes'" || echo '?')"

if [ $ONLY_PGR = 1 ]; then
    PLAN="pgRouting 대조만"
    TAKES="10~15분"
else
    PLAN="알고리즘 대조 → 동시 요청 스윕$( [ $TWICE = 1 ] && echo ' ×2' )$( [ $WITH_PGR = 1 ] && echo ' → pgRouting 대조' )"
    TAKES="30분~1시간"
fi

cat <<EOF

  측정 계획
  ─────────────────────────────────────────────
  저장소      $ROOT ($(git rev-parse --short HEAD 2>/dev/null || echo '?'))
  활성 빌드   #$ACTIVE_BUILD
  CPU · 메모리 $(nproc) vCPU · $(free -m | awk '/^Mem:/{print $2}')MB · 스왑 $(free -m | awk '/^Swap:/{print $2}')MB
  측정        $PLAN
  최근 사용   지난 30분 동안 행렬 계산 ${RECENT_RUNS}건$( [ "$RECENT_RUNS" != "0" ] && echo '  ← 누가 쓰고 있다. 새벽에 돌리는 게 낫다' )
  힙          스윕 $SWEEP_HEAP (운영 기본값과 같다) · 나머지 $PERF_HEAP
  결과        $OUT.tar.gz

  ⚠ 측정하는 동안 서버 컨테이너를 내린다. 서비스가 멈춘다.
    끝나면(실패해도) 다시 띄운다. ${TAKES} 걸린다.

EOF

if [ $DRY_RUN = 1 ]; then
    say "--dry-run: 확인만 했다. 아무것도 바꾸지 않았다"
    exit 0
fi

if [ $ASSUME_YES = 0 ]; then
    read -r -p "계속할까? [y/N] " answer
    [[ "$answer" =~ ^[yY]$ ]] || { say "그만둔다"; exit 0; }
fi

mkdir -p "$OUT"

# ── 2. JDK 25 ───────────────────────────────────────────────────────────────

JDK_DIR="$HOME/.jdks/temurin-25"
if [ ! -x "$JDK_DIR/bin/java" ]; then
    case "$(uname -m)" in
        x86_64) JDK_ARCH=x64 ;;
        aarch64|arm64) JDK_ARCH=aarch64 ;;
        *) die "모르는 CPU: $(uname -m)" ;;
    esac
    say "JDK 25 받는 중 (Eclipse Temurin, $JDK_ARCH)"
    mkdir -p "$JDK_DIR"
    curl -fsSL "https://api.adoptium.net/v3/binary/latest/25/ga/linux/$JDK_ARCH/jdk/hotspot/normal/eclipse" \
        | tar -xz --strip-components=1 -C "$JDK_DIR"
fi
export JAVA_HOME="$JDK_DIR"
say "JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# ── 3. 환경 기록 · 정리 예약 · 서버 내리기 ─────────────────────────────────────

{
    echo "# 측정 환경"
    echo
    echo "- 시각: $(TZ=Asia/Seoul date '+%F %T') KST"
    echo "- 커밋: $(git rev-parse HEAD 2>/dev/null || echo '?')"
    echo "- 활성 그래프 빌드: #$ACTIVE_BUILD"
    echo "- 커널: $(uname -srm)"
    echo "- CPU: $(nproc) vCPU · $(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -1)"
    echo "- 메모리: $(free -m | awk '/^Mem:/{print $2}')MB · 스왑 $(free -m | awk '/^Swap:/{print $2}')MB"
    echo "- JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
    echo "- 힙: 스윕 $SWEEP_HEAP · 나머지 $PERF_HEAP · Gradle $GRADLE_JVM"
    echo
    echo '```'
    docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}'
    echo '```'
} > "$OUT/env.md"

VMSTAT_PID=""
DBMEM_PID=""
SERVER_STOPPED=0

cleanup() {
    local code=$?
    trap - EXIT INT TERM   # INT 로 들어와 exit 할 때 EXIT 로 한 번 더 돌지 않게
    [ -n "$VMSTAT_PID" ] && kill "$VMSTAT_PID" 2>/dev/null || true
    [ -n "$DBMEM_PID" ] && kill "$DBMEM_PID" 2>/dev/null || true

    say "정리: 측정용 표가 남았으면 지운다"
    psql_q "DROP TABLE IF EXISTS perf_od_cache; DROP TABLE IF EXISTS perf_pgr_edges;" >/dev/null 2>&1 || true
    if [ "$PGR_BEFORE" = "0" ]; then
        psql_q "DROP EXTENSION IF EXISTS pgrouting;" >/dev/null 2>&1 || true
    fi

    if [ $SERVER_STOPPED = 1 ]; then
        say "정리: 서버 컨테이너를 다시 띄운다"
        docker compose start server >/dev/null 2>&1 || echo "✗ 서버를 다시 못 띄웠다 — docker compose up -d 로 직접" >&2
    fi
    exit $code
}
trap cleanup EXIT INT TERM

say "서버 컨테이너를 내린다"
docker compose stop server >/dev/null
SERVER_STOPPED=1

# 내린 직후의 메모리 — 측정이 쓸 수 있는 몫
free -m > "$OUT/free-before.txt"

# ── 4. 기록 시작 ────────────────────────────────────────────────────────────

vmstat -n -t 5 > "$OUT/vmstat.log" &
VMSTAT_PID=$!

(
    echo "time,db_mem"
    while true; do
        echo "$(TZ=Asia/Seoul date +%T),$(docker stats --no-stream --format '{{.MemUsage}}' "$DB" 2>/dev/null | cut -d/ -f1 | tr -d ' ')"
        sleep 4
    done
) > "$OUT/dbmem.log" &
DBMEM_PID=$!

# ── 5. pgRouting (선택) ─────────────────────────────────────────────────────

if [ $WITH_PGR = 1 ]; then
    if docker exec "$DB" test -f /usr/share/postgresql/17/extension/pgrouting.control; then
        say "pgRouting 은 이미 컨테이너에 있다"
    else
        # postgis/postgis:17-3.5 는 Debian bullseye 이고, PostgreSQL 저장소가 bullseye 를 아카이브로 옮겼다.
        say "pgRouting 을 DB 컨테이너에 넣는다 (컨테이너 안에서만 — 이미지는 그대로)"
        docker exec "$DB" bash -c "
            echo 'deb [ signed-by=/usr/local/share/keyrings/postgres.gpg.asc ] http://apt-archive.postgresql.org/pub/repos/apt/ bullseye-pgdg-archive main' \
                > /etc/apt/sources.list.d/pgdg.list &&
            apt-get update -qq &&
            DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends postgresql-17-pgrouting" \
            > "$OUT/pgrouting-install.log" 2>&1 \
            || die "pgRouting 설치 실패 — $OUT/pgrouting-install.log"
    fi
fi

# ── 6. 측정 ─────────────────────────────────────────────────────────────────

GRADLE=(./gradlew --no-daemon -q "-Dorg.gradle.jvmargs=$GRADLE_JVM")

step() {
    local name="$1" report="$2"; shift 2
    local t0; t0=$(date +%s)
    say "$name 시작"
    echo "## $name — $(TZ=Asia/Seoul date +%T) 시작" >> "$OUT/run.log"
    if (cd server && "${GRADLE[@]}" "$@") >> "$OUT/run.log" 2>&1; then
        cp "server/build/reports/perf/$report" "$OUT/$name.md"
        say "$name 끝 ($(( ($(date +%s) - t0) / 60 ))분) → $name.md"
        echo "- $name: $(( ($(date +%s) - t0) / 60 ))분" >> "$OUT/steps.txt"
    else
        say "✗ $name 실패 — $OUT/run.log 끝부분을 본다"
        echo "- $name: 실패" >> "$OUT/steps.txt"
    fi
}

# 컴파일을 먼저 따로 한다. 측정 회차 안에 컴파일 시간이 섞이지 않게.
say "컴파일"
(cd server && "${GRADLE[@]}" testClasses) >> "$OUT/run.log" 2>&1 || die "컴파일 실패 — $OUT/run.log"

if [ $ONLY_PGR = 0 ]; then
    step algorithms   algorithms.md   algorithmTest "-PperfHeap=$PERF_HEAP"
    step concurrency  concurrency.md  sweepTest     "-PsweepHeap=$SWEEP_HEAP"
    if [ $TWICE = 1 ]; then
        step concurrency-2 concurrency.md sweepTest "-PsweepHeap=$SWEEP_HEAP"
    fi
fi
if [ $WITH_PGR = 1 ]; then
    step pgrouting pgrouting.md pgroutingTest "-PperfHeap=$PERF_HEAP"
fi

kill "$VMSTAT_PID" "$DBMEM_PID" 2>/dev/null || true
VMSTAT_PID=""
DBMEM_PID=""

# ── 7. 요약 ─────────────────────────────────────────────────────────────────

# vmstat 열 이름은 procps 판마다 조금 다르다. 머리줄에서 위치를 찾는다.
vm_summary() {
    awk '
        /us +sy +id/ && !hdr { for (i = 1; i <= NF; i++) col[$i] = i; hdr = 1; next }
        hdr && $1 ~ /^[0-9]+$/ {
            if (++seen == 1) next   # 첫 줄은 부팅 이후 평균이다
            n++; us += $col["us"]; sy += $col["sy"]; st += $col["st"]
            if ($col["st"] > stmax) stmax = $col["st"]
            si += $col["si"]; so += $col["so"]
        }
        END {
            if (!n) { print "- vmstat 기록 없음"; exit }
            printf "- 표본 %d개 (5초 간격)\n", n
            printf "- CPU 평균: 사용자 %.0f%% · 시스템 %.0f%% · **steal 평균 %.0f%% · 최대 %d%%**\n", us/n, sy/n, st/n, stmax
            printf "- 스왑: 들어옴 %d · 나감 %d (KB/s 누적)%s\n", si, so, (si + so > 0 ? " — **측정 중 스왑이 돌았다. 그 구간 숫자는 메모리 압박이 섞였다**" : " — 측정 중 스왑 없음")
        }' "$OUT/vmstat.log"
}

db_summary() {
    awk -F, 'NR > 1 && $2 != "" {
        v = $2; mul = 1
        if (v ~ /GiB$/) mul = 1024; else if (v ~ /KiB$/) mul = 1 / 1024
        gsub(/[A-Za-z]/, "", v); v = v * mul
        if (v > max) max = v; if (!min || v < min) min = v; n++
    } END {
        if (!n) { print "- 기록 없음"; exit }
        printf "- DB 컨테이너 메모리: 최소 %.0f MB · **최대 %.0f MB** (표본 %d개)\n", min, max, n
    }' "$OUT/dbmem.log"
}

{
    echo "# 서버 측정 요약"
    echo
    cat "$OUT/env.md" | sed '1,2d'
    echo
    echo "## 단계"
    echo
    cat "$OUT/steps.txt" 2>/dev/null || echo "- (없음)"
    echo
    echo "## 측정 동안의 VM"
    echo
    vm_summary
    db_summary
    echo
    echo "steal 은 하이퍼바이저가 이 VM 에 CPU 를 주지 않은 시간의 비율이다. e2-small 은 공유 코어라"
    echo "오래 바쁘면 이 값이 올라간다 — 그 시간만큼 측정 숫자가 느려진 것은 코드가 아니라 VM 탓이다."
    echo "DB 메모리 최대치가 pgRouting 단계에서 튀었다면 그게 DB 안에서 그래프를 짓는 몫이다(dbmem.log 의 시각과"
    echo "run.log 의 단계 시각을 맞춰 본다)."
} > "$OUT/summary.md"

tar -czf "$OUT.tar.gz" -C "$ROOT/perf-results" "server-$STAMP"
say "끝. 결과: $OUT.tar.gz"
echo
echo "  로컬로 가져오기:"
echo "    gcloud compute scp <인스턴스>:$OUT.tar.gz . --zone <존>"
echo
