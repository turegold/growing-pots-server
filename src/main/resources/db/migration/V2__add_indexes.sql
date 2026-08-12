-- 성능 측정으로 효과가 검증된 인덱스만 반영한다. 측정 방법과 채택/보류 근거는 docs/perf 참고.
-- 복합 인덱스 컬럼 순서는 "선택도 높은 컬럼 먼저" — student_profile_id(학생당 40~80행)로
-- 먼저 좁힌 뒤 저카디널리티 컬럼(status, applied_division_id)을 보조 조건으로 쓴다.

-- 이수내역 status 필터 조회 대응. course_id까지 포함시킨 커버링 인덱스 —
-- 이 쿼리는 course_id만 SELECT하므로 테이블 접근 없이 인덱스만으로 끝난다.
CREATE INDEX `idx_sc_profile_status_course`
    ON `student_course` (`student_profile_id`, `status`, `course_id`);

-- 이수구분별 이수과목 조회 대응. 단일 컬럼 인덱스 2개의 index_merge intersect로
-- 수만 행을 읽던 것을 복합 인덱스 탐색 한 번으로 바꾼다.
CREATE INDEX `idx_sc_profile_division`
    ON `student_course` (`student_profile_id`, `applied_division_id`);

-- 학과별 현재 개설 과목 조회 대응.
CREATE INDEX `idx_course_dept_active`
    ON `course` (`offering_department_id`, `is_active`);

-- 과목 키워드 검색용. LIKE '%키워드%'는 선행 와일드카드라 B-Tree를 탈 수 없어
-- ngram Full-Text 검색으로 대체한다(기본 ngram_token_size=2, 2글자 단위 색인).
-- name·course_code를 복합 인덱스 하나로 만드는 이유: 검색은 두 컬럼을 OR로 조회하는데,
-- 컬럼별 Full-Text 인덱스 2개를 MATCH ... OR MATCH ...로 묶으면 옵티마이저가
-- 인덱스를 못 타고 테이블 풀스캔으로 떨어진다(EXPLAIN ANALYZE 실측).
ALTER TABLE `course` ADD FULLTEXT INDEX `ft_course_name_code` (`name`, `course_code`) WITH PARSER ngram;
