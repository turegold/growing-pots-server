package com.growingpots.domain.admin.service;

import com.growingpots.domain.admin.client.GoogleSheetsReader;
import com.growingpots.domain.admin.dto.response.SeedDataSyncResponse;
import com.growingpots.domain.admin.dto.response.SyncError;
import com.growingpots.domain.admin.dto.response.TableSyncResult;
import com.growingpots.domain.admin.dto.sheet.CourseRow;
import com.growingpots.domain.admin.dto.sheet.DepartmentRow;
import com.growingpots.domain.admin.dto.sheet.DivisionRow;
import com.growingpots.domain.admin.dto.sheet.RequirementCourseRow;
import com.growingpots.domain.admin.dto.sheet.SchoolRow;
import com.growingpots.domain.university.entity.Course;
import com.growingpots.domain.university.entity.Department;
import com.growingpots.domain.university.entity.Division;
import com.growingpots.domain.university.entity.GeArea;
import com.growingpots.domain.university.entity.RequirementCourse;
import com.growingpots.domain.university.entity.School;
import com.growingpots.domain.university.entity.enums.DivisionCategory;
import com.growingpots.domain.university.entity.enums.OpenedSemester;
import com.growingpots.domain.university.repository.CourseRepository;
import com.growingpots.domain.university.repository.DepartmentRepository;
import com.growingpots.domain.university.repository.DivisionRepository;
import com.growingpots.domain.university.repository.GeAreaRepository;
import com.growingpots.domain.university.repository.RequirementCourseRepository;
import com.growingpots.domain.university.repository.SchoolRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 구글시트(School/Department/Division/Course/RequirementCourse)를 읽어 검증 후 DB에 반영한다.
// 의존관계 순서(School -> Department/Division -> Course -> RequirementCourse)대로 처리하면서,
// 이번 배치에서 새로 생기는 상위 엔티티도 다음 단계에서 바로 참조할 수 있도록 조회 맵에 누적한다.
// 행 하나의 검증 실패가 나머지 행 처리를 막지 않는다 - 실패한 행만 건너뛰고 사유를 모아 리턴한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class SeedDataSyncService {

    private static final int HEADER_ROW_OFFSET = 2; // 1행=헤더, 데이터는 2행부터

    private final GoogleSheetsReader sheetsReader;
    private final SchoolRepository schoolRepository;
    private final DepartmentRepository departmentRepository;
    private final DivisionRepository divisionRepository;
    private final GeAreaRepository geAreaRepository;
    private final CourseRepository courseRepository;
    private final RequirementCourseRepository requirementCourseRepository;

    @Transactional
    public SeedDataSyncResponse sync() throws IOException {
        List<SyncError> errors = new ArrayList<>();

        Map<String, School> schoolsByName = preloadSchools();
        TableSyncResult schoolResult = syncSchools(schoolsByName, errors);

        Map<String, Department> departmentsByName = preloadDepartments(schoolsByName);
        TableSyncResult departmentResult = syncDepartments(schoolsByName, departmentsByName, errors);

        Map<DivisionCategory, Division> divisionsByCategory = preloadDivisions(schoolsByName);
        TableSyncResult divisionResult = syncDivisions(schoolsByName, divisionsByCategory, errors);

        Map<String, GeArea> geAreasByName = preloadGeAreas(schoolsByName);

        TableSyncResult courseResult = syncCourses(
                schoolsByName, departmentsByName, divisionsByCategory, geAreasByName, errors);

        TableSyncResult requirementCourseResult = syncRequirementCourses(
                departmentsByName, divisionsByCategory, errors);

        return new SeedDataSyncResponse(
                schoolResult, departmentResult, divisionResult, courseResult, requirementCourseResult, errors);
    }

    // ------------------------------------------------------------------ School

    private Map<String, School> preloadSchools() {
        Map<String, School> map = new HashMap<>();
        schoolRepository.findAll().forEach(s -> map.put(s.getName(), s));
        return map;
    }

    private TableSyncResult syncSchools(Map<String, School> schoolsByName, List<SyncError> errors) throws IOException {
        List<SchoolRow> rows = sheetsReader.readSchools();
        int applied = 0;
        int skipped = 0;
        for (int i = 0; i < rows.size(); i++) {
            SchoolRow row = rows.get(i);
            if (row.name().isBlank()) {
                errors.add(new SyncError("School", i + HEADER_ROW_OFFSET, "학교명이 비어있습니다"));
                skipped++;
                continue;
            }
            schoolsByName.computeIfAbsent(row.name(), name -> schoolRepository.save(School.builder().name(name).build()));
            applied++;
        }
        return TableSyncResult.of(applied, skipped);
    }

    // ------------------------------------------------------------------ Department

    private Map<String, Department> preloadDepartments(Map<String, School> schoolsByName) {
        Map<String, Department> map = new HashMap<>();
        for (School school : schoolsByName.values()) {
            departmentRepository.findBySchoolId(school.getId())
                    .forEach(d -> map.put(departmentKey(school.getName(), d.getName()), d));
        }
        return map;
    }

    private TableSyncResult syncDepartments(
            Map<String, School> schoolsByName, Map<String, Department> departmentsByName, List<SyncError> errors
    ) throws IOException {
        List<DepartmentRow> rows = sheetsReader.readDepartments();
        int applied = 0;
        int skipped = 0;
        for (int i = 0; i < rows.size(); i++) {
            DepartmentRow row = rows.get(i);
            int sheetRow = i + HEADER_ROW_OFFSET;
            if (row.name().isBlank() || row.college().isBlank()) {
                errors.add(new SyncError("Department", sheetRow, "학과명 또는 단과대학명이 비어있습니다"));
                skipped++;
                continue;
            }
            School school = schoolsByName.get(row.school());
            if (school == null) {
                errors.add(new SyncError("Department", sheetRow, "학교 '" + row.school() + "'를 찾을 수 없습니다"));
                skipped++;
                continue;
            }
            String key = departmentKey(school.getName(), row.name());
            Department department = departmentsByName.get(key);
            if (department == null) {
                department = departmentRepository.save(
                        Department.builder().school(school).college(row.college()).name(row.name()).build());
                departmentsByName.put(key, department);
            } else {
                department.updateCollege(row.college());
            }
            applied++;
        }
        return TableSyncResult.of(applied, skipped);
    }

    private static String departmentKey(String schoolName, String departmentName) {
        return schoolName + "::" + departmentName;
    }

    // ------------------------------------------------------------------ Division

    private Map<DivisionCategory, Division> preloadDivisions(Map<String, School> schoolsByName) {
        Map<DivisionCategory, Division> map = new HashMap<>();
        for (School school : schoolsByName.values()) {
            divisionRepository.findBySchool(school).forEach(d -> map.put(d.getCategory(), d));
        }
        return map;
    }

    private TableSyncResult syncDivisions(
            Map<String, School> schoolsByName, Map<DivisionCategory, Division> divisionsByCategory, List<SyncError> errors
    ) throws IOException {
        List<DivisionRow> rows = sheetsReader.readDivisions();
        int applied = 0;
        int skipped = 0;
        Set<DivisionCategory> alreadyCreatedThisRun = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            DivisionRow row = rows.get(i);
            int sheetRow = i + HEADER_ROW_OFFSET;
            School school = schoolsByName.get(row.school());
            if (school == null) {
                errors.add(new SyncError("Division", sheetRow, "학교 '" + row.school() + "'를 찾을 수 없습니다"));
                skipped++;
                continue;
            }
            var categoryOpt = KhuDivisionCategoryMapping.resolve(row.categoryName());
            if (categoryOpt.isEmpty()) {
                // 매핑이 의도적으로 없는 코드(선수과목/종합시험 등) - 에러가 아니라 그냥 스킵.
                skipped++;
                continue;
            }
            DivisionCategory category = categoryOpt.get();
            // 여러 사강 원본 코드가 같은 category로 합쳐질 수 있어, 이번 실행에서 이미 만들었으면 code만 최초값 유지.
            if (!divisionsByCategory.containsKey(category)) {
                Division division = divisionRepository.save(
                        Division.builder().school(school).code(row.code()).category(category).build());
                divisionsByCategory.put(category, division);
            }
            alreadyCreatedThisRun.add(category);
            applied++;
        }
        return TableSyncResult.of(applied, skipped);
    }

    // ------------------------------------------------------------------ GeArea (읽기 전용 - 이미 시드되어 있다고 가정)

    private Map<String, GeArea> preloadGeAreas(Map<String, School> schoolsByName) {
        Map<String, GeArea> map = new HashMap<>();
        for (School school : schoolsByName.values()) {
            geAreaRepository.findBySchool(school).forEach(a -> map.put(normalizeAreaName(a.getName()), a));
        }
        return map;
    }

    // DB에 이미 시드된 이름("생명, 우주, 인간")과 크롤러가 뽑은 이름("생명,우주,인간")이 쉼표 뒤
    // 공백 유무로 다르다 - 실제 크롤링 데이터로 직접 확인한 차이라 공백을 지우고 비교한다.
    private static String normalizeAreaName(String name) {
        return name.replaceAll("\\s+", "");
    }

    // ------------------------------------------------------------------ Course

    private TableSyncResult syncCourses(
            Map<String, School> schoolsByName,
            Map<String, Department> departmentsByName,
            Map<DivisionCategory, Division> divisionsByCategory,
            Map<String, GeArea> geAreasByName,
            List<SyncError> errors
    ) throws IOException {
        List<CourseRow> rows = sheetsReader.readCourses();
        int applied = 0;
        int skipped = 0;
        Set<String> courseCodesInSheet = new HashSet<>();
        Map<String, Course> coursesByCode = preloadCourses(schoolsByName);

        for (int i = 0; i < rows.size(); i++) {
            CourseRow row = rows.get(i);
            int sheetRow = i + HEADER_ROW_OFFSET;

            if (row.courseCode().isBlank() || row.name().isBlank()) {
                errors.add(new SyncError("Course", sheetRow, "학수번호 또는 과목명이 비어있습니다"));
                skipped++;
                continue;
            }
            School school = schoolsByName.get(row.school());
            if (school == null) {
                errors.add(new SyncError("Course", sheetRow, "학교 '" + row.school() + "'를 찾을 수 없습니다"));
                skipped++;
                continue;
            }

            Department offeringDepartment = null;
            if (!row.offeringDepartment().isBlank()) {
                offeringDepartment = departmentsByName.get(departmentKey(school.getName(), row.offeringDepartment()));
                if (offeringDepartment == null) {
                    errors.add(new SyncError("Course", sheetRow,
                            "학과 '" + row.offeringDepartment() + "'를 찾을 수 없습니다"));
                    skipped++;
                    continue;
                }
            }

            Division defaultDivision = null;
            if (!row.defaultDivision().isBlank()) {
                var categoryOpt = KhuDivisionCategoryMapping.resolve(row.defaultDivision());
                defaultDivision = categoryOpt.map(divisionsByCategory::get).orElse(null);
                // 매핑이 의도적으로 없는 이수구분(선수과목 등)이면 defaultDivision을 null로 두고 계속 진행한다 - 에러 아님.
            }

            GeArea geArea = null;
            if (!row.geArea().isBlank()) {
                geArea = geAreasByName.get(normalizeAreaName(row.geArea()));
                if (geArea == null) {
                    errors.add(new SyncError("Course", sheetRow,
                            "배분이수영역 '" + row.geArea() + "'를 찾을 수 없습니다(GeArea는 미리 시드되어 있어야 함)"));
                    skipped++;
                    continue;
                }
            }

            int credit;
            OpenedSemester openedSemester;
            Integer recommendedYearLow;
            Integer recommendedYearHigh;
            boolean isEnglish;
            boolean isActive;
            try {
                credit = (int) Double.parseDouble(row.credit());
                openedSemester = OpenedSemester.valueOf(row.openedSemester());
                recommendedYearLow = row.recommendedYearLow().isBlank() ? null : Integer.valueOf(row.recommendedYearLow());
                recommendedYearHigh = row.recommendedYearHigh().isBlank() ? null : Integer.valueOf(row.recommendedYearHigh());
                isEnglish = Boolean.parseBoolean(row.isEnglish());
                isActive = Boolean.parseBoolean(row.isActive());
            } catch (RuntimeException e) {
                errors.add(new SyncError("Course", sheetRow, "값 형식이 올바르지 않습니다: " + e.getMessage()));
                skipped++;
                continue;
            }

            Course course = coursesByCode.get(row.courseCode());
            if (course == null) {
                course = courseRepository.save(Course.builder()
                        .school(school)
                        .courseCode(row.courseCode())
                        .name(row.name())
                        .credit(credit)
                        .offeringDepartment(offeringDepartment)
                        .defaultDivision(defaultDivision)
                        .geArea(geArea)
                        .recommendedYearLow(recommendedYearLow)
                        .recommendedYearHigh(recommendedYearHigh)
                        .openedSemester(openedSemester)
                        .isEnglish(isEnglish)
                        .isSw(false)
                        .isActive(isActive)
                        .build());
                coursesByCode.put(row.courseCode(), course);
            } else {
                course.updateFromSync(
                        row.name(), credit, offeringDepartment, defaultDivision, geArea,
                        recommendedYearLow, recommendedYearHigh, openedSemester, isEnglish, isActive);
            }
            courseCodesInSheet.add(row.courseCode());
            applied++;
        }

        int deactivated = deactivateMissingCourses(coursesByCode, courseCodesInSheet);
        return TableSyncResult.ofCourse(applied, skipped, deactivated);
    }

    // school당 한 번씩만 전체 스캔해서 courseCode로 인덱싱한다 - School/Department/Division과
    // 동일한 미리 로드 패턴. 이 맵을 syncCourses의 upsert 조회와 deactivateMissingCourses의
    // "시트에서 사라진 과목 찾기"가 함께 재사용해 course 테이블을 두 번 스캔하지 않는다.
    private Map<String, Course> preloadCourses(Map<String, School> schoolsByName) {
        Map<String, Course> map = new HashMap<>();
        for (School school : schoolsByName.values()) {
            courseRepository.findBySchool(school).forEach(c -> map.put(c.getCourseCode(), c));
        }
        return map;
    }

    // 이번 시트에 전혀 없는(=행 자체가 사라진) 기존 학수번호를 안전장치로 비활성화한다.
    // 정상적인 경우엔 이미 시트의 is_active 컬럼값이 courseRepository에 그대로 반영되어 있으므로,
    // 여기 걸리는 건 시트에서 행 자체가 삭제된 극단적인 경우뿐이다.
    private int deactivateMissingCourses(Map<String, Course> coursesByCode, Set<String> courseCodesInSheet) {
        int deactivated = 0;
        for (Course course : coursesByCode.values()) {
            if (course.isActive() && !courseCodesInSheet.contains(course.getCourseCode())) {
                course.deactivate();
                deactivated++;
            }
        }
        return deactivated;
    }

    // ------------------------------------------------------------------ RequirementCourse

    private TableSyncResult syncRequirementCourses(
            Map<String, Department> departmentsByName,
            Map<DivisionCategory, Division> divisionsByCategory,
            List<SyncError> errors
    ) throws IOException {
        List<RequirementCourseRow> rows = sheetsReader.readRequirementCourses();
        int applied = 0;
        int skipped = 0;

        for (int i = 0; i < rows.size(); i++) {
            RequirementCourseRow row = rows.get(i);
            int sheetRow = i + HEADER_ROW_OFFSET;

            if (row.department().isBlank()) {
                errors.add(new SyncError("RequirementCourse", sheetRow, "학과명이 비어있습니다"));
                skipped++;
                continue;
            }

            // RequirementCourse 시트는 school 컬럼이 없다 - 지금은 단일 학교(경희대)라 department
            // 이름만으로 유일하게 찾을 수 있어야 한다. 여러 학교를 지원하게 되면 이 부분을 다시 봐야 한다.
            Department department = departmentsByName.values().stream()
                    .filter(d -> d.getName().equals(row.department()))
                    .findFirst()
                    .orElse(null);
            if (department == null) {
                errors.add(new SyncError("RequirementCourse", sheetRow, "학과 '" + row.department() + "'를 찾을 수 없습니다"));
                skipped++;
                continue;
            }

            Division division = null;
            if (!row.division().isBlank()) {
                var categoryOpt = KhuDivisionCategoryMapping.resolve(row.division());
                division = categoryOpt.map(divisionsByCategory::get).orElse(null);
                if (division == null) {
                    errors.add(new SyncError("RequirementCourse", sheetRow,
                            "이수구분 '" + row.division() + "'을 찾을 수 없습니다"));
                    skipped++;
                    continue;
                }
            }

            int baseYear;
            int minCount;
            int minCredit;
            try {
                baseYear = Integer.parseInt(row.baseYear());
                minCount = row.minCount().isBlank() ? 0 : Integer.parseInt(row.minCount());
                minCredit = row.minCredit().isBlank() ? 0 : Integer.parseInt(row.minCredit());
            } catch (NumberFormatException e) {
                errors.add(new SyncError("RequirementCourse", sheetRow, "숫자 형식이 올바르지 않습니다: " + e.getMessage()));
                skipped++;
                continue;
            }

            RequirementCourse requirementCourse = requirementCourseRepository
                    .findForUpsert(department, division, baseYear, row.name())
                    .orElse(null);
            if (requirementCourse == null) {
                requirementCourseRepository.save(RequirementCourse.builder()
                        .department(department)
                        .division(division)
                        .name(row.name())
                        .baseYear(baseYear)
                        .minCount(minCount)
                        .minCredit(minCredit)
                        .build());
            } else {
                requirementCourse.updateFromSync(row.name(), minCount, minCredit);
            }
            applied++;
        }
        return TableSyncResult.of(applied, skipped);
    }
}
