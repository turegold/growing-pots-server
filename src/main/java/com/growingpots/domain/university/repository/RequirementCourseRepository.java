package com.growingpots.domain.university.repository;

import com.growingpots.domain.university.entity.Department;
import com.growingpots.domain.university.entity.Division;
import com.growingpots.domain.university.entity.RequirementCourse;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RequirementCourseRepository extends JpaRepository<RequirementCourse, Long> {

    // 시드 데이터 동기화 시 upsert 대상을 찾는 자연키 조회. division은 null일 수 있는데(학과
    // 독립 요건), JPA 파생 쿼리의 "= :division"은 null과 절대 매치되지 않아 직접 분기해야 한다.
    // name도 키에 포함해야 한다 - 실제로 같은 학과+연도에 division=null인 독립 요건이 "전문실기",
    // "맨손체조"처럼 여러 개 존재하는 걸 확인함(스포츠의학과 2019). department+division+baseYear
    // 만으로는 이 둘을 구분하지 못해 findForUpsert가 2건을 반환해 동기화가 예외로 실패했었다.
    @Query("SELECT rc FROM RequirementCourse rc WHERE rc.department = :department "
            + "AND ((:division IS NULL AND rc.division IS NULL) OR rc.division = :division) "
            + "AND rc.baseYear = :baseYear AND rc.name = :name")
    Optional<RequirementCourse> findForUpsert(
            @Param("department") Department department,
            @Param("division") Division division,
            @Param("baseYear") int baseYear,
            @Param("name") String name);

    @Query("SELECT rc FROM RequirementCourse rc WHERE rc.department = :department "
            + "AND rc.division = :division AND rc.baseYear <= :admissionYear")
    List<RequirementCourse> findApplicable(
            @Param("department") Department department,
            @Param("division") Division division,
            @Param("admissionYear") int admissionYear);

    // division이 없는(이수구분과 무관한 학과 독립 졸업요건) row만 조회. 스포츠의학과 졸업필수 등.
    @Query("SELECT rc FROM RequirementCourse rc WHERE rc.department = :department "
            + "AND rc.division IS NULL AND rc.baseYear <= :admissionYear")
    List<RequirementCourse> findGraduationRequiredByDepartment(
            @Param("department") Department department,
            @Param("admissionYear") int admissionYear);

    // 복수전공 학생의 학과들을 한 번에 조회하기 위한 배치 버전 (findGraduationRequiredByDepartment와 조건 동일).
    @Query("SELECT rc FROM RequirementCourse rc WHERE rc.department IN :departments "
            + "AND rc.division IS NULL AND rc.baseYear <= :admissionYear")
    List<RequirementCourse> findGraduationRequiredByDepartmentIn(
            @Param("departments") List<Department> departments,
            @Param("admissionYear") int admissionYear);
}