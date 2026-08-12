# 인덱스 성능 측정

`growingpots_perf` DB에서 인덱스 적용 전후를 비교한다. 개발용 `growingpots_v2`는 건드리지 않는다.

## 1. 측정용 DB 준비 (최초 1회)

```bash
mysql -u root -p -e "CREATE DATABASE growingpots_perf CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

# Flyway로 스키마 생성
DB_NAME=growingpots_perf ./gradlew bootRun \
  --args="--spring.main.web-application-type=none --server.port=0"

# 시드 데이터 (course 5,000 / student_course 60,000)
mysql -u root -p growingpots_perf < docs/perf/seed_perf_data.sql
```

`seed_perf_data.sql`은 재실행 가능하고 결정적이다. `RAND()`를 쓰지 않아 몇 번을 돌려도 같은 데이터가 나온다.

## 2. 측정

```bash
# ① 인덱스 없는 상태
python3 docs/perf/benchmark.py before

# ② 인덱스 적용
mysql -u root -p growingpots_perf < docs/perf/indexes_add.sql

# ③ 적용 후 — before와 자동 비교표까지 출력된다
python3 docs/perf/benchmark.py after
```

되돌리려면:

```bash
mysql -u root -p growingpots_perf < docs/perf/indexes_drop.sql
```

## 무엇을 재는가

| 지표 | 왜 보는가 |
|---|---|
| **읽은 행 수** | 응답시간은 머신 부하에 흔들리지만 이 값은 흔들리지 않는다. 개선의 근거로 더 단단하다 |
| 실행계획 (`type`, `key`, `Extra`) | 인덱스를 실제로 탔는지. `Using index`가 뜨면 커버링 인덱스가 동작한 것 |
| **count 쿼리 (Q1C/Q1FC)** | `Page<Course>`는 조회와 별개로 `LIMIT` 없는 count를 한 번 더 날린다. 조건에 맞는 행 전부를 훑어야 끝나므로 LIKE 비용의 진짜 크기는 조회(Q1)가 아니라 count 쪽에 있다 |
| p50 / p95 응답시간 | 체감 성능 |
| **쓰기 비용** | 인덱스는 읽기를 빠르게 하는 대신 쓰기를 느리게 한다. 한쪽만 제시하면 정직한 측정이 아니다 |

실행시간은 클라이언트 왕복이 섞이지 않도록 `EXPLAIN ANALYZE`가 보고하는 값을 쓴다.

## 옵션

| 환경변수 | 기본값 | 설명 |
|---|---|---|
| `RUNS` | 15 | 쿼리당 측정 횟수 (워밍업 3회 별도) |
| `DB_NAME` | `growingpots_perf` | 대상 DB |
| `DB_PASSWORD` | `application-secret.yml`에서 읽음 | 없으면 프롬프트 |
| `NO_COLOR` | — | 색 끄기 (로그 파일로 남길 때) |

측정 파라미터(`student_profile_id` 등)는 **실행 시점에 DB에서 조회**한다. 하드코딩하면 시드를 다시 만들 때 AUTO_INCREMENT가 밀려 0행을 재게 된다.

## 주의

- 결과 JSON(`results/`)은 gitignore 대상이다. 머신마다 값이 달라 저장소에 남길 이유가 없다.
- `indexes_add.sql`은 **실험용**이다. 효과가 검증된 인덱스만 골라 정식 마이그레이션(`V2__*.sql`)으로 옮긴다.
