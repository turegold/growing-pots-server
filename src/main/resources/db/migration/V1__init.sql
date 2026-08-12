-- Growing Pots 초기 스키마

-- 엔티티 정의로부터 생성한 baseline. 이후 변경은 V2 이상의 마이그레이션으로만 한다.

-- ─── 테이블 ───

CREATE TABLE `cert_result` (
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `student_major_id` bigint NOT NULL,
    `student_profile_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `cert_type` enum('ENGLISH','GRADUATION_CERT','SW','THESIS','TOPIK') NOT NULL,
    `result` enum('EXEMPT','FAIL','NONE','PASS') NOT NULL,
    `source` enum('MANUAL','PDF') NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `course` (
    `credit` int NOT NULL,
    `is_active` tinyint(1) NOT NULL DEFAULT '1',
    `is_english` bit(1) NOT NULL,
    `is_sw` bit(1) NOT NULL,
    `recommended_year_high` int DEFAULT NULL,
    `recommended_year_low` int DEFAULT NULL,
    `created_at` datetime(6) NOT NULL,
    `default_division_id` bigint DEFAULT NULL,
    `ge_area_id` bigint DEFAULT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `offering_department_id` bigint DEFAULT NULL,
    `school_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `course_code` varchar(255) NOT NULL,
    `name` varchar(255) NOT NULL,
    `opened_semester` enum('BOTH','FIRST','SECOND') DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `course_prerequisite` (
    `course_id` bigint NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `department_id` bigint DEFAULT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `required_course_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `prerequisite_type` enum('RECOMMENDED','REQUIRED') NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `cross_major_recognized_course` (
    `course_id` bigint NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `recognized_division_id` bigint NOT NULL,
    `target_department_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `department` (
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `school_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `college` varchar(255) NOT NULL,
    `name` varchar(255) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `division` (
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `school_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `code` varchar(255) NOT NULL,
    `category` enum('DISTRIBUTED_GE','FREE_GE','GENERAL_ELECTIVE','MAJOR_BASIC','MAJOR_ELECTIVE','MAJOR_REQUIRED','REQUIRED_GE') NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `ge_area` (
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `school_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `code` varchar(255) NOT NULL,
    `name` varchar(255) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `graduation_analysis_summary` (
    `distributed_ge_current` int DEFAULT NULL,
    `distributed_ge_required` int DEFAULT NULL,
    `english_current` int NOT NULL,
    `english_required` int NOT NULL,
    `free_ge_current` int DEFAULT NULL,
    `free_ge_required` int DEFAULT NULL,
    `general_elective_current` int NOT NULL,
    `gpa_current` decimal(5,3) DEFAULT NULL,
    `gpa_required` decimal(5,3) DEFAULT NULL,
    `major_basic_current` int NOT NULL,
    `major_basic_required` int NOT NULL,
    `major_elective_current` int NOT NULL,
    `major_elective_required` int NOT NULL,
    `major_required_current` int NOT NULL,
    `major_required_required` int NOT NULL,
    `required_ge_current` int DEFAULT NULL,
    `required_ge_required` int DEFAULT NULL,
    `required_plus_elective_current` int NOT NULL,
    `required_plus_elective_required` int NOT NULL,
    `sw_cert_current` int DEFAULT NULL,
    `sw_cert_required` int DEFAULT NULL,
    `total_credit_current` int NOT NULL,
    `total_credit_required` int NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `student_major_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UKlh0hsg4ej0mbyin9j4lcwylku` (`student_major_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `member` (
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `updated_at` datetime(6) NOT NULL,
    `refresh_token` varchar(512) DEFAULT NULL,
    `email` varchar(255) DEFAULT NULL,
    `nickname` varchar(255) NOT NULL,
    `oauth_id` varchar(255) NOT NULL,
    `oauth_provider` enum('KAKAO') NOT NULL,
    `role` enum('ADMIN','USER') NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UKq04eskgongimwb9ge43hyqp53` (`oauth_provider`,`oauth_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `planner_simulation` (
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `student_profile_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `name` varchar(255) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UKnsf22gmdscuudtta5e07uelb7` (`student_profile_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `planner_term` (
    `semester` int NOT NULL,
    `year_level` int NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `planner_simulation_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `planner_term_version` (
    `is_selected` bit(1) NOT NULL,
    `version_no` int NOT NULL,
    `version_order` int NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `planner_term_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `name` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `planner_version_item` (
    `course_position_order` int NOT NULL,
    `credit` int NOT NULL,
    `course_id` bigint NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `planned_division_id` bigint DEFAULT NULL,
    `planner_term_version_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `requirement_course` (
    `base_year` int NOT NULL,
    `min_count` int NOT NULL,
    `min_credit` int NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `department_id` bigint NOT NULL,
    `division_id` bigint DEFAULT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `updated_at` datetime(6) NOT NULL,
    `name` varchar(255) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `requirement_course_item` (
    `course_id` bigint NOT NULL,
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `requirement_course_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `school` (
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `updated_at` datetime(6) NOT NULL,
    `name` varchar(255) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK251hwtk4rvkoblr76wknh8v41` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `student_course` (
    `credit` int NOT NULL,
    `display_order` int NOT NULL DEFAULT '0',
    `is_retake` bit(1) NOT NULL,
    `taken_year` int DEFAULT NULL,
    `applied_department_id` bigint DEFAULT NULL,
    `applied_division_id` bigint DEFAULT NULL,
    `course_id` bigint DEFAULT NULL,
    `created_at` datetime(6) NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `student_profile_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `raw_course_code` varchar(255) DEFAULT NULL,
    `raw_course_name` varchar(255) NOT NULL,
    `source` enum('MANUAL','PDF') NOT NULL,
    `status` enum('COMPLETED','IN_PROGRESS') NOT NULL,
    `taken_semester` enum('FIRST','SECOND','SUMMER','WINTER') DEFAULT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `student_major` (
    `created_at` datetime(6) NOT NULL,
    `department_id` bigint NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `student_profile_id` bigint NOT NULL,
    `track_id` bigint DEFAULT NULL,
    `updated_at` datetime(6) NOT NULL,
    `major_type` enum('DOUBLE','MAIN') NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `student_profile` (
    `admission_year` int NOT NULL,
    `current_grade` int DEFAULT NULL,
    `current_term` int DEFAULT NULL,
    `created_at` datetime(6) NOT NULL,
    `department_id` bigint NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `member_id` bigint NOT NULL,
    `onboarding_confirmed_at` datetime(6) DEFAULT NULL,
    `school_id` bigint NOT NULL,
    `updated_at` datetime(6) NOT NULL,
    `enrollment_status` varchar(255) DEFAULT NULL,
    `student_no` varchar(255) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UKsc16sfn5271xtnv76kix5dmgw` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `track` (
    `created_at` datetime(6) NOT NULL,
    `department_id` bigint NOT NULL,
    `id` bigint NOT NULL AUTO_INCREMENT,
    `updated_at` datetime(6) NOT NULL,
    `name` varchar(255) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ─── 유니크 제약 ───

-- 학수번호는 학교 단위로 유일하다. 초기 ERD 문서에는 있었으나 엔티티에 반영되지 않아
-- 실제 스키마에서 누락돼 있었다. CourseRepository가 학수번호로 단건 조회를 하므로
-- 제약이 없으면 학교가 늘어날 때 NonUniqueResultException이 발생한다.
ALTER TABLE `course`
    ADD CONSTRAINT `uk_course_school_code` UNIQUE (`school_id`, `course_code`);

-- ─── 외래키 제약 ───

ALTER TABLE `cert_result`
    ADD CONSTRAINT `fk_cert_result_student_profile` FOREIGN KEY (`student_profile_id`) REFERENCES `student_profile` (`id`);

ALTER TABLE `cert_result`
    ADD CONSTRAINT `fk_cert_result_student_major` FOREIGN KEY (`student_major_id`) REFERENCES `student_major` (`id`);

ALTER TABLE `course`
    ADD CONSTRAINT `fk_course_offering_department` FOREIGN KEY (`offering_department_id`) REFERENCES `department` (`id`);

ALTER TABLE `course`
    ADD CONSTRAINT `fk_course_ge_area` FOREIGN KEY (`ge_area_id`) REFERENCES `ge_area` (`id`);

ALTER TABLE `course`
    ADD CONSTRAINT `fk_course_default_division` FOREIGN KEY (`default_division_id`) REFERENCES `division` (`id`);

ALTER TABLE `course`
    ADD CONSTRAINT `fk_course_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`id`);

ALTER TABLE `course_prerequisite`
    ADD CONSTRAINT `fk_course_prerequisite_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);

ALTER TABLE `course_prerequisite`
    ADD CONSTRAINT `fk_course_prerequisite_department` FOREIGN KEY (`department_id`) REFERENCES `department` (`id`);

ALTER TABLE `course_prerequisite`
    ADD CONSTRAINT `fk_course_prerequisite_required_course` FOREIGN KEY (`required_course_id`) REFERENCES `course` (`id`);

ALTER TABLE `cross_major_recognized_course`
    ADD CONSTRAINT `fk_cross_major_recognized_course_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);

ALTER TABLE `cross_major_recognized_course`
    ADD CONSTRAINT `fk_cross_major_recognized_course_recognized_division` FOREIGN KEY (`recognized_division_id`) REFERENCES `division` (`id`);

ALTER TABLE `cross_major_recognized_course`
    ADD CONSTRAINT `fk_cross_major_recognized_course_target_department` FOREIGN KEY (`target_department_id`) REFERENCES `department` (`id`);

ALTER TABLE `department`
    ADD CONSTRAINT `fk_department_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`id`);

ALTER TABLE `division`
    ADD CONSTRAINT `fk_division_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`id`);

ALTER TABLE `ge_area`
    ADD CONSTRAINT `fk_ge_area_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`id`);

ALTER TABLE `graduation_analysis_summary`
    ADD CONSTRAINT `fk_graduation_analysis_summary_student_major` FOREIGN KEY (`student_major_id`) REFERENCES `student_major` (`id`);

ALTER TABLE `planner_simulation`
    ADD CONSTRAINT `fk_planner_simulation_student_profile` FOREIGN KEY (`student_profile_id`) REFERENCES `student_profile` (`id`);

ALTER TABLE `planner_term`
    ADD CONSTRAINT `fk_planner_term_planner_simulation` FOREIGN KEY (`planner_simulation_id`) REFERENCES `planner_simulation` (`id`);

ALTER TABLE `planner_term_version`
    ADD CONSTRAINT `fk_planner_term_version_planner_term` FOREIGN KEY (`planner_term_id`) REFERENCES `planner_term` (`id`);

ALTER TABLE `planner_version_item`
    ADD CONSTRAINT `fk_planner_version_item_planned_division` FOREIGN KEY (`planned_division_id`) REFERENCES `division` (`id`);

ALTER TABLE `planner_version_item`
    ADD CONSTRAINT `fk_planner_version_item_planner_term_version` FOREIGN KEY (`planner_term_version_id`) REFERENCES `planner_term_version` (`id`);

ALTER TABLE `planner_version_item`
    ADD CONSTRAINT `fk_planner_version_item_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);

ALTER TABLE `requirement_course`
    ADD CONSTRAINT `fk_requirement_course_department` FOREIGN KEY (`department_id`) REFERENCES `department` (`id`);

ALTER TABLE `requirement_course`
    ADD CONSTRAINT `fk_requirement_course_division` FOREIGN KEY (`division_id`) REFERENCES `division` (`id`);

ALTER TABLE `requirement_course_item`
    ADD CONSTRAINT `fk_requirement_course_item_requirement_course` FOREIGN KEY (`requirement_course_id`) REFERENCES `requirement_course` (`id`);

ALTER TABLE `requirement_course_item`
    ADD CONSTRAINT `fk_requirement_course_item_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);

ALTER TABLE `student_course`
    ADD CONSTRAINT `fk_student_course_course` FOREIGN KEY (`course_id`) REFERENCES `course` (`id`);

ALTER TABLE `student_course`
    ADD CONSTRAINT `fk_student_course_applied_department` FOREIGN KEY (`applied_department_id`) REFERENCES `department` (`id`);

ALTER TABLE `student_course`
    ADD CONSTRAINT `fk_student_course_student_profile` FOREIGN KEY (`student_profile_id`) REFERENCES `student_profile` (`id`);

ALTER TABLE `student_course`
    ADD CONSTRAINT `fk_student_course_applied_division` FOREIGN KEY (`applied_division_id`) REFERENCES `division` (`id`);

ALTER TABLE `student_major`
    ADD CONSTRAINT `fk_student_major_student_profile` FOREIGN KEY (`student_profile_id`) REFERENCES `student_profile` (`id`);

ALTER TABLE `student_major`
    ADD CONSTRAINT `fk_student_major_department` FOREIGN KEY (`department_id`) REFERENCES `department` (`id`);

ALTER TABLE `student_major`
    ADD CONSTRAINT `fk_student_major_track` FOREIGN KEY (`track_id`) REFERENCES `track` (`id`);

ALTER TABLE `student_profile`
    ADD CONSTRAINT `fk_student_profile_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`);

ALTER TABLE `student_profile`
    ADD CONSTRAINT `fk_student_profile_department` FOREIGN KEY (`department_id`) REFERENCES `department` (`id`);

ALTER TABLE `student_profile`
    ADD CONSTRAINT `fk_student_profile_school` FOREIGN KEY (`school_id`) REFERENCES `school` (`id`);

ALTER TABLE `track`
    ADD CONSTRAINT `fk_track_department` FOREIGN KEY (`department_id`) REFERENCES `department` (`id`);
