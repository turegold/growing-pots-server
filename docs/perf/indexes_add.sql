-- 인덱스 성능 실험용 — 측정 중에만 쓰는 파일이다.
-- 실험으로 효과가 검증된 것만 골라 정식 마이그레이션(V2)으로 옮긴다.
--
--   mysql -u root -p growingpots_perf < docs/perf/indexes_add.sql
--   mysql -u root -p growingpots_perf < docs/perf/indexes_drop.sql   (되돌리기)
--
-- 설계 근거
-- - 복합 인덱스는 "선택도 높은 컬럼 먼저, 범위/저카디널리티 나중" 순서를 따른다.
-- - student_profile_id는 학생 1명당 40~80행이라 6만 행에서 선택도가 압도적으로 높다.
-- - status는 값이 2개뿐(COMPLETED 90% / IN_PROGRESS 10%)이라 단독으로는 인덱스 가치가 없다.
--   첫 컬럼으로 좁힌 뒤의 보조 조건으로만 의미가 있다.

-- ─────────────────────────────────────────────────────────────
-- student_course
-- ─────────────────────────────────────────────────────────────

-- findCourseIdsByStudentProfileAndStatusIn 대응.
-- course_id까지 포함시킨 커버링 인덱스 — 이 쿼리는 course_id만 SELECT하므로
-- 테이블 접근 없이 인덱스만으로 끝낼 수 있다(EXPLAIN의 Extra에 Using index).
CREATE INDEX idx_sc_profile_status_course
    ON student_course (student_profile_id, status, course_id);

-- findByStudentProfileAndAppliedDivisionIn 대응 (이수구분별 과목 조회).
CREATE INDEX idx_sc_profile_division
    ON student_course (student_profile_id, applied_division_id);

-- ─────────────────────────────────────────────────────────────
-- course
-- ─────────────────────────────────────────────────────────────

-- findActiveByDepartmentAndDivisionCategory 대응 (미이수 후보 목록).
CREATE INDEX idx_course_dept_active
    ON course (offering_department_id, is_active);

-- 과목 검색의 기본 필터(school_id + is_active) 대응.
-- 학교가 1개뿐인 현 데이터에서는 효과가 제한적이다 — 다학교로 확장할 때를 대비한 것이며,
-- 측정 결과 이득이 없으면 채택하지 않는 것이 맞다.
CREATE INDEX idx_course_school_active
    ON course (school_id, is_active);

-- 키워드 검색용 Full-Text 인덱스.
-- LIKE '%키워드%'는 선행 와일드카드라 B-Tree를 절대 탈 수 없다. 인덱스를 아무리 붙여도
-- 소용없으므로 접근 방식 자체를 바꾼다. 한국어는 공백 기준 토큰화가 통하지 않아
-- ngram 파서를 쓴다(기본 ngram_token_size=2, 즉 2글자 단위로 색인).
ALTER TABLE course ADD FULLTEXT INDEX ft_course_name (name) WITH PARSER ngram;
