-- 인덱스 성능 측정용 시드 데이터 생성 스크립트
--
-- 대상 DB: growingpots_perf (개발용 growingpots_v2와 분리)
-- 실행 전제: 빈 DB에 Flyway V1이 적용돼 있어야 한다.
--   mysql -u root -p -e "CREATE DATABASE growingpots_perf CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
--   DB_NAME=growingpots_perf ./gradlew bootRun --args="--spring.main.web-application-type=none --server.port=0"
-- 실행:
--   mysql -u root -p growingpots_perf < docs/perf/seed_perf_data.sql
--
-- 설계 원칙
-- 1) 마스터 데이터(학교/학과/이수구분/영역)와 실제 과목 836건은 growingpots_v2에서 그대로 복사한다.
--    과목명이 실제 한글 강의명이어야 LIKE 검색 측정이 의미를 갖는다.
-- 2) 합성 과목은 실제 과목명에 접미사를 붙여 만든다. 무작위 문자열을 쓰면 키워드 검색의
--    선택도가 비현실적으로 낮아져 인덱스 효과가 과대평가된다.
-- 3) 컬럼별 카디널리티를 실제 분포에 맞춘다. status처럼 값이 2개뿐인 컬럼을 균등분포로
--    만들면 복합 인덱스의 컬럼 순서 판단이 왜곡된다.
-- 4) 결정적(deterministic)으로 생성한다. RAND() 대신 모듈러 연산을 써서 몇 번을 돌려도
--    같은 데이터가 나오도록 한다 - 측정을 재현할 수 있어야 한다.

SET SESSION cte_max_recursion_depth = 1000000;
SET FOREIGN_KEY_CHECKS = 1;

-- ─────────────────────────────────────────────────────────────
-- 0. 기존 데이터 정리 (재실행 가능하도록)
-- ─────────────────────────────────────────────────────────────
DELETE FROM planner_version_item;
DELETE FROM planner_term_version;
DELETE FROM planner_term;
DELETE FROM planner_simulation;
DELETE FROM graduation_analysis_summary;
DELETE FROM cert_result;
DELETE FROM student_course;
DELETE FROM student_major;
DELETE FROM student_profile;
DELETE FROM member;
DELETE FROM requirement_course_item;
DELETE FROM requirement_course;
DELETE FROM cross_major_recognized_course;
DELETE FROM course_prerequisite;
DELETE FROM course;
DELETE FROM ge_area;
DELETE FROM division;
DELETE FROM department;
DELETE FROM school;

-- ─────────────────────────────────────────────────────────────
-- 1. 마스터 데이터 + 실제 과목 836건 복사
--    컬럼 순서가 DB마다 다를 수 있으므로 반드시 컬럼명을 명시한다.
-- ─────────────────────────────────────────────────────────────
INSERT INTO school (id, created_at, updated_at, name)
SELECT id, created_at, updated_at, name FROM growingpots_v2.school;

INSERT INTO department (id, created_at, updated_at, school_id, college, name)
SELECT id, created_at, updated_at, school_id, college, name FROM growingpots_v2.department;

INSERT INTO division (id, created_at, updated_at, school_id, code, category)
SELECT id, created_at, updated_at, school_id, code, category FROM growingpots_v2.division;

INSERT INTO ge_area (id, created_at, updated_at, school_id, code, name)
SELECT id, created_at, updated_at, school_id, code, name FROM growingpots_v2.ge_area;

INSERT INTO course (id, created_at, updated_at, school_id, course_code, name, credit,
                    offering_department_id, default_division_id, ge_area_id,
                    recommended_year_low, recommended_year_high, opened_semester,
                    is_english, is_sw, is_active)
SELECT id, created_at, updated_at, school_id, course_code, name, credit,
       offering_department_id, default_division_id, ge_area_id,
       recommended_year_low, recommended_year_high, opened_semester,
       is_english, is_sw, is_active
FROM growingpots_v2.course;

-- ─────────────────────────────────────────────────────────────
-- 2. 합성 과목 생성 → 총 5,000건
--    실제 과목명 + 접미사 조합. 접미사는 실제 강의명에 흔한 패턴을 골랐다.
-- ─────────────────────────────────────────────────────────────
CREATE TEMPORARY TABLE tmp_base_course AS
SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn, name, credit, offering_department_id,
       default_division_id, ge_area_id, recommended_year_low, recommended_year_high, opened_semester
FROM course;

SET @base_cnt = (SELECT COUNT(*) FROM tmp_base_course);
SET @target_extra = 5000 - @base_cnt;

INSERT INTO course (created_at, updated_at, school_id, course_code, name, credit,
                    offering_department_id, default_division_id, ge_area_id,
                    recommended_year_low, recommended_year_high, opened_semester,
                    is_english, is_sw, is_active)
WITH RECURSIVE seq AS (
    SELECT 1 AS n
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @target_extra
)
SELECT
    NOW(6), NOW(6),
    1,
    -- 학수번호: 실제 형태([A-Z]{2,5}\d{3,5})를 따르되 유니크 제약을 위반하지 않도록 별도 접두사
    CONCAT('PF', LPAD(s.n, 5, '0')),
    -- 접미사를 MOD로 고르면 원본 개수(836)와 접미사 개수(8)의 공약수 때문에 조합이 반복돼
    -- 같은 이름이 여러 건 생긴다. DIV로 골라 원본을 한 바퀴 돌 때마다 접미사를 바꾼다.
    CONCAT(b.name, ELT(((s.n DIV @base_cnt) % 7) + 1, ' 1', ' 2', '심화', '실습', '세미나', '특강', '연습')),
    b.credit,
    b.offering_department_id,
    b.default_division_id,
    b.ge_area_id,
    b.recommended_year_low,
    b.recommended_year_high,
    b.opened_semester,
    -- 영어강의 약 10%, SW인증 약 15% (실제 비율에 근사)
    (s.n % 10 = 0),
    (s.n % 7 = 0),
    -- 폐지 과목 약 8%: is_active 인덱스의 선택도를 현실적으로 만든다
    (s.n % 12 <> 0)
FROM seq s
JOIN tmp_base_course b ON b.rn = ((s.n - 1) % @base_cnt) + 1;

DROP TEMPORARY TABLE tmp_base_course;

-- ─────────────────────────────────────────────────────────────
-- 3. 학생 1,000명 (member → student_profile → student_major)
-- ─────────────────────────────────────────────────────────────
INSERT INTO member (created_at, updated_at, nickname, oauth_id, oauth_provider, role, email, refresh_token)
WITH RECURSIVE seq AS (
    SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 1000
)
SELECT NOW(6), NOW(6), CONCAT('perf학생', n), CONCAT('PERF_OAUTH_', n), 'KAKAO', 'USER', NULL, NULL
FROM seq;

CREATE TEMPORARY TABLE tmp_member AS
SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn, id FROM member;

-- 학과 ID는 연속이 아니다(중간에 빠진 번호가 있음). MIN(id)+offset 방식은 없는 ID를
-- 만들어내므로, 순번을 매긴 임시 테이블에 조인해서 실제 존재하는 ID만 쓴다.
CREATE TEMPORARY TABLE tmp_dept AS
SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn, id FROM department;

SET @dept_cnt = (SELECT COUNT(*) FROM tmp_dept);

INSERT INTO student_profile (created_at, updated_at, member_id, school_id, department_id,
                             admission_year, current_grade, current_term,
                             enrollment_status, student_no, onboarding_confirmed_at)
SELECT NOW(6), NOW(6), m.id, 1,
       d.id,
       -- 입학년도 2019~2026: 배분이수 영역 판정(2024년 컷오프) 양쪽이 다 나오도록
       2019 + (m.rn % 8),
       1 + (m.rn % 4), 1 + (m.rn % 2),
       '재학', CONCAT(2019 + (m.rn % 8), LPAD(m.rn, 6, '0')), NOW(6)
FROM tmp_member m
JOIN tmp_dept d ON d.rn = ((m.rn - 1) % @dept_cnt) + 1;

CREATE TEMPORARY TABLE tmp_profile AS
SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn, id, department_id FROM student_profile;

-- 본전공: 전원
INSERT INTO student_major (created_at, updated_at, student_profile_id, department_id, track_id, major_type)
SELECT NOW(6), NOW(6), p.id, p.department_id, NULL, 'MAIN' FROM tmp_profile p;

-- 복수전공: 30% (실제로 복수전공 학생이 소수이므로 전수로 만들면 비현실적)
INSERT INTO student_major (created_at, updated_at, student_profile_id, department_id, track_id, major_type)
SELECT NOW(6), NOW(6), p.id, d.id, NULL, 'DOUBLE'
FROM tmp_profile p
JOIN tmp_dept d ON d.rn = ((p.rn + 5) % @dept_cnt) + 1
WHERE p.rn % 10 < 3
  AND d.id <> p.department_id;

-- ─────────────────────────────────────────────────────────────
-- 4. 이수내역 — 학생당 40~80건(평균 60) → 약 60,000건
--    status는 COMPLETED 90% / IN_PROGRESS 10%.
--    (2개뿐인 값을 균등분포로 만들면 복합 인덱스 컬럼 순서 판단이 왜곡된다)
-- ─────────────────────────────────────────────────────────────
CREATE TEMPORARY TABLE tmp_course AS
SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn, id, name, credit, default_division_id
FROM course WHERE is_active = 1;

SET @course_cnt = (SELECT COUNT(*) FROM tmp_course);

INSERT INTO student_course (created_at, updated_at, student_profile_id, course_id,
                            applied_division_id, applied_department_id,
                            raw_course_code, raw_course_name, credit,
                            taken_year, taken_semester, is_retake, status, source, display_order)
WITH RECURSIVE seq AS (
    SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 80
)
SELECT
    NOW(6), NOW(6), p.id, c.id,
    c.default_division_id, NULL,
    NULL, c.name, c.credit,
    2019 + ((p.rn + s.n) % 7),
    ELT(((p.rn + s.n) % 4) + 1, 'FIRST', 'SECOND', 'SUMMER', 'WINTER'),
    ((p.rn + s.n) % 20 = 0),                       -- 재수강 약 5%
    IF((p.rn * 80 + s.n) % 10 = 0, 'IN_PROGRESS', 'COMPLETED'),
    'PDF',
    s.n - 1
FROM tmp_profile p
JOIN seq s
-- 과목 배정: 학생마다 연속 블록을 주면 학생 간 겹치는 과목이 거의 없어 비현실적이다.
-- 서로소인 계수로 흩어 인기 과목이 여러 학생에게 겹치도록 하되, 한 학생 안에서는
-- 61과 과목 수가 서로소라 60여 개가 모두 다른 과목이 된다.
JOIN tmp_course c ON c.rn = (((p.rn - 1) * 37 + (s.n - 1) * 61) % @course_cnt) + 1
-- 학생당 이수 건수를 40~80으로 흩는다(평균 60). 전원이 정확히 같은 건수면
-- student_profile_id 기준 인덱스의 실제 동작을 과소평가하게 된다.
WHERE s.n <= 40 + ((p.rn * 17) % 41);

DROP TEMPORARY TABLE tmp_course;
DROP TEMPORARY TABLE tmp_profile;
DROP TEMPORARY TABLE tmp_dept;
DROP TEMPORARY TABLE tmp_member;

-- ─────────────────────────────────────────────────────────────
-- 5. 결과 확인
-- ─────────────────────────────────────────────────────────────
SELECT 'course' AS tbl, COUNT(*) AS cnt FROM course
UNION ALL SELECT 'member', COUNT(*) FROM member
UNION ALL SELECT 'student_profile', COUNT(*) FROM student_profile
UNION ALL SELECT 'student_major', COUNT(*) FROM student_major
UNION ALL SELECT 'student_course', COUNT(*) FROM student_course;
