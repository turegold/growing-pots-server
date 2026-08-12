-- 새 growingpots_perf DB를 만들 때 딱 한 번만 실행한다.
-- (Flyway V1 적용 직후, seed_perf_data.sql이나 indexes_add.sql보다 먼저)
--
--   DB_NAME=growingpots_perf ./gradlew bootRun --args="..."   ← V1 적용
--   mysql -u root -p growingpots_perf < docs/perf/prepare_indexes.sql   ← 이 파일
--   mysql -u root -p growingpots_perf < docs/perf/seed_perf_data.sql
--
-- 무엇을, 왜
-- V1__init.sql이 FK 제약을 추가할 때, 그 컬럼에 인덱스가 하나도 없으면 MySQL이 자동으로
-- 단일 컬럼 인덱스를 만들어 FK를 지원한다(student_course.student_profile_id,
-- course.offering_department_id가 그렇다). 문제는 이 자동 생성 인덱스가 "언제든 대체
-- 가능한 것"으로 취급된다는 점이다 — 나중에 그 컬럼을 리딩 컬럼으로 하는 더 넓은 인덱스
-- (예: indexes_add.sql의 idx_sc_profile_division)를 추가하면, 자동 생성 인덱스는 그 자리에서
-- 조용히 제거되고 FK는 새 인덱스에 갈아탄다. 실제로 CREATE INDEX 한 줄만 실행해도 바로
-- 사라지는 것까지 확인했다(별도 DROP 없이).
--
-- 그러면 그 넓은 인덱스가 FK를 혼자 떠받치는 상태가 되고, indexes_drop.sql로 되돌리려는
-- 순간 "needed in a foreign key constraint" 에러로 막힌다 — 실제로 겪은 문제다.
--
-- 해결: 같은 인덱스를 EXPLICIT하게(CREATE INDEX로 직접) 다시 만들어 두면, 그 뒤로는 자동
-- 제거 대상에서 빠진다(실측 확인: 이후 idx_sc_profile_division을 추가해도 살아남았다).
-- 다만 자동 생성 인덱스가 그 순간 FK의 유일한 지지대라 곧바로 DROP은 막힌다. 그래서
-- 임시 인덱스로 먼저 지지대를 만들어 자동 제거를 유도한 뒤, 같은 이름으로 다시 만들고
-- 임시 인덱스를 치운다.

-- 1) 임시 인덱스 생성 — 이 시점에 원래 자동 생성 인덱스가 조용히 제거된다.
CREATE INDEX tmp_fk_sc_profile ON student_course (student_profile_id);
CREATE INDEX tmp_fk_course_dept ON course (offering_department_id);

-- 2) 같은 이름으로 명시적 재생성 — 이제부터 자동 제거 대상이 아니다.
CREATE INDEX fk_student_course_student_profile ON student_course (student_profile_id);
CREATE INDEX fk_course_offering_department ON course (offering_department_id);

-- 3) 임시 인덱스 정리
DROP INDEX tmp_fk_sc_profile ON student_course;
DROP INDEX tmp_fk_course_dept ON course;
