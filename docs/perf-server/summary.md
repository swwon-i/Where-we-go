# 서버 측정 요약

- 시각: 2026-11-11 00:56:11 KST
- 커밋: a0349e825a16f1a2130bd17867ce286ff0b9b0d1
- 활성 그래프 빌드: #22
- 커널: Linux 7.0.0-1011-gcp x86_64
- CPU: 2 vCPU · AMD EPYC 7B12
- 메모리: 1959MB · 스왑 2047MB
- JDK: openjdk version "25.0.5" 2026-10-20 LTS
- 힙: 스윕 512m · 나머지 768m · Gradle -Xmx512m

```
NAME         CPU %     MEM USAGE / LIMIT
wwg-server   0.19%     191.2MiB / 1.913GiB
wwg-caddy    0.02%     12.52MiB / 1.913GiB
wwg-db       0.01%     112.3MiB / 1.913GiB
```

## 단계

- algorithms: 3분
- concurrency: 25분
- concurrency-2: 25분

## 측정 동안의 VM

- 표본 626개 (5초 간격)
- CPU 평균: 사용자 64% · 시스템 10% · **steal 평균 0% · 최대 0%**
- 스왑: 들어옴 23885 · 나감 70158 (KB/s 누적) — **측정 중 스왑이 돌았다. 그 구간 숫자는 메모리 압박이 섞였다**
- DB 컨테이너 메모리: 최소 111 MB · **최대 289 MB** (표본 465개)

steal 은 하이퍼바이저가 이 VM 에 CPU 를 주지 않은 시간의 비율이다. e2-small 은 공유 코어라
오래 바쁘면 이 값이 올라간다 — 그 시간만큼 측정 숫자가 느려진 것은 코드가 아니라 VM 탓이다.
DB 메모리 최대치가 pgRouting 단계에서 튀었다면 그게 DB 안에서 그래프를 짓는 몫이다(dbmem.log 의 시각과
run.log 의 단계 시각을 맞춰 본다).
