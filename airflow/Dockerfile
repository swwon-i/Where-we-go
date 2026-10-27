# Airflow + ETL 의존성. 로컬 학습용이다 — 서버의 일일 실행은 cron(etl/run_daily.sh) 그대로다.
#
# 컨텍스트는 etl/ 이다(airflow/docker-compose.yml). 루트 .dockerignore 가 etl/ 을 빼 두었기 때문이다.
# 여기서는 requirements.txt 만 쓴다. 코드는 굽지 않고 볼륨으로 붙인다 —
# 굽으면 고칠 때마다 다시 빌드해야 하고, 잊으면 예전 코드가 조용히 돈다(2026-10-27 서버에서 그랬다).
FROM apache/airflow:3.3.2-python3.12

COPY requirements.txt /tmp/etl-requirements.txt

# apache-airflow 를 같은 버전으로 함께 적는다. 빠뜨리면 pip 가 의존성을 맞추다 Airflow 를 바꿔 버릴 수 있다.
RUN pip install --no-cache-dir "apache-airflow==${AIRFLOW_VERSION}" -r /tmp/etl-requirements.txt
