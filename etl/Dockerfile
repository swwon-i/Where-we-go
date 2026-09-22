# 인허가 받기·적재용 이미지. 서버에 파이썬을 따로 깔지 않고 일일 실행을 돌린다.
#
#   docker compose --profile etl run --rm etl etl.fetch --source all
#   docker compose --profile etl run --rm etl etl.ingest --source all
#
# 컨텍스트는 etl/ 이다. 코드를 /app/etl 에 두어 `python -m etl.…` 이 그대로 돈다.
# csv/ 와 etl/out/ 은 볼륨으로 붙인다 (docker-compose.yml).
#
# 그래프 빌드(build_graph, OSMnx)는 넣지 않는다 — 의존성이 무겁고 매일 돌 일이 아니다.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONUTF8=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

# 의존성 레이어를 코드와 분리해 코드만 바뀌면 다시 받지 않는다.
COPY requirements.txt /app/etl/requirements.txt
RUN pip install -r /app/etl/requirements.txt

COPY . /app/etl

# root 로 돌리지 않는다. 볼륨(csv/, etl/out/)에 쓸 수 있도록 uid 는 호스트 사용자와 맞춘다 (기본 1000).
ARG UID=1000
RUN useradd --system --uid ${UID} --create-home wwg && mkdir -p /app/etl/out && chown -R wwg /app
USER wwg

ENTRYPOINT ["python", "-X", "utf8", "-m"]
CMD ["etl.ingest", "--help"]
