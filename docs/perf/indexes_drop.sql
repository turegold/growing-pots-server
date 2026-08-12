-- indexes_add.sql 되돌리기. before/after를 반복 측정하기 위한 것이다.
-- IF EXISTS를 쓰지 않는 이유: 없는 인덱스를 지우려 할 때 조용히 넘어가면
-- 어떤 상태에서 측정했는지 알 수 없게 된다. 에러로 드러나는 편이 낫다.

DROP INDEX idx_sc_profile_status_course ON student_course;
DROP INDEX idx_sc_profile_division ON student_course;
DROP INDEX idx_course_dept_active ON course;
DROP INDEX idx_course_school_active ON course;
DROP INDEX ft_course_name ON course;
