package com.growingpots.domain.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growingpots.domain.admin.client.GoogleSheetsReader;
import com.growingpots.domain.admin.dto.sheet.CourseRow;
import com.growingpots.domain.admin.dto.sheet.DepartmentRow;
import com.growingpots.domain.admin.dto.sheet.DivisionRow;
import com.growingpots.domain.admin.dto.sheet.RequirementCourseRow;
import com.growingpots.domain.admin.dto.sheet.SchoolRow;
import com.growingpots.domain.university.entity.Course;
import com.growingpots.domain.university.entity.Department;
import com.growingpots.domain.university.entity.Division;
import com.growingpots.domain.university.entity.RequirementCourse;
import com.growingpots.domain.university.entity.School;
import com.growingpots.domain.university.entity.enums.DivisionCategory;
import com.growingpots.domain.university.entity.enums.OpenedSemester;
import com.growingpots.domain.university.repository.CourseRepository;
import com.growingpots.domain.university.repository.DepartmentRepository;
import com.growingpots.domain.university.repository.DivisionRepository;
import com.growingpots.domain.university.repository.RequirementCourseRepository;
import com.growingpots.domain.university.repository.SchoolRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// 구글시트 -> DB 동기화 엔드포인트의 통합 테스트. GoogleSheetsReader는 실제 구글시트 API를 호출하므로
// @MockitoBean으로 대체하고, 그 아래(검증/upsert/DB반영)는 실제 H2로 끝까지 태운다.
// 이번 세션에서 실제로 로컬 MySQL 검증 중 터진 두 버그(RequirementCourse 자연키에 name 누락으로
// 인한 IncorrectResultSizeDataAccessException, Course upsert가 행마다 findByCourseCode를 날리던
// N+1)를 각각 재현해 회귀를 막는다.
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class SeedDataSyncControllerTest {

    private static final String SYNC_TOKEN = "test-admin-sync-token"; // application-test.yml의 admin.sync-token과 일치해야 함

    @Autowired private MockMvc mockMvc;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private DivisionRepository divisionRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private RequirementCourseRepository requirementCourseRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private GoogleSheetsReader sheetsReader;

    @Test
    void 토큰이_올바르지_않으면_401을_반환한다() throws Exception {
        sync("wrong-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CMN_005"));
    }

    @Test
    void 시트_데이터를_동기화하면_학교_학과_이수구분_과목_졸업요건이_모두_반영된다() throws Exception {
        String suffix = "동기화1";
        String schoolName = "동기화테스트대학교-" + suffix;
        String departmentName = "테스트학과-" + suffix;

        when(sheetsReader.readSchools()).thenReturn(List.of(new SchoolRow(schoolName)));
        when(sheetsReader.readDepartments()).thenReturn(
                List.of(new DepartmentRow(schoolName, "공과대학", departmentName)));
        when(sheetsReader.readDivisions()).thenReturn(
                List.of(new DivisionRow(schoolName, "04", "전공필수")));
        when(sheetsReader.readCourses()).thenReturn(List.of(new CourseRow(
                schoolName, "SYNC101", "테스트과목", "3", departmentName, "전공필수", "",
                "1", "2", "BOTH", "false", "true", "2026")));
        when(sheetsReader.readRequirementCourses()).thenReturn(List.of(
                new RequirementCourseRow(departmentName, "", "학과졸업요건", "2020", "1", "0")));

        sync(SYNC_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.school.applied").value(1))
                .andExpect(jsonPath("$.data.department.applied").value(1))
                .andExpect(jsonPath("$.data.division.applied").value(1))
                .andExpect(jsonPath("$.data.course.applied").value(1))
                .andExpect(jsonPath("$.data.requirementCourse.applied").value(1))
                .andExpect(jsonPath("$.data.errors").isEmpty());

        Course course = courseRepository.findByCourseCode("SYNC101").orElseThrow();
        assertThat(course.getName()).isEqualTo("테스트과목");
        assertThat(course.getCredit()).isEqualTo(3);
        assertThat(course.getOpenedSemester()).isEqualTo(OpenedSemester.BOTH);
        assertThat(course.isActive()).isTrue();

        // course.getDefaultDivision()/getOfferingDepartment()는 지연 로딩 프록시라 MockMvc 요청이
        // 끝나 세션이 닫힌 뒤에는 getId() 외의 getter를 부르면 LazyInitializationException이 난다.
        // 프록시의 id로 리포지토리를 다시 조회해 확인한다.
        Division division = divisionRepository.findById(course.getDefaultDivision().getId()).orElseThrow();
        assertThat(division.getCategory()).isEqualTo(DivisionCategory.MAJOR_REQUIRED);
        Department offeringDepartment = departmentRepository.findById(course.getOfferingDepartment().getId()).orElseThrow();
        assertThat(offeringDepartment.getName()).isEqualTo(departmentName);

        Department department = departmentRepository.findAll().stream()
                .filter(d -> d.getName().equals(departmentName))
                .findFirst().orElseThrow();
        RequirementCourse requirementCourse = requirementCourseRepository.findAll().stream()
                .filter(rc -> rc.getDepartment().getId().equals(department.getId()))
                .findFirst().orElseThrow();
        assertThat(requirementCourse.getName()).isEqualTo("학과졸업요건");
        assertThat(requirementCourse.getDivision()).isNull();
        assertThat(requirementCourse.getBaseYear()).isEqualTo(2020);
    }

    // 회귀 테스트: 스포츠의학과 2019년 사례("전문실기"/"맨손체조")처럼 같은 학과+이수구분없음+기준연도인데
    // 이름만 다른 학과 독립 졸업요건이 실제로 2건 존재한다. findForUpsert 자연키에 name이 빠져 있으면
    // 2건이 매치되어 IncorrectResultSizeDataAccessException으로 동기화 전체가 500으로 실패했었다.
    @Test
    void 같은_학과와_기준연도여도_이름이_다른_독립_졸업요건_두_건은_모두_저장되고_재동기화해도_안전하다() throws Exception {
        String suffix = "동기화2";
        String schoolName = "동기화테스트대학교-" + suffix;
        String departmentName = "테스트학과-" + suffix;

        when(sheetsReader.readSchools()).thenReturn(List.of(new SchoolRow(schoolName)));
        when(sheetsReader.readDepartments()).thenReturn(
                List.of(new DepartmentRow(schoolName, "체육대학", departmentName)));
        when(sheetsReader.readDivisions()).thenReturn(List.of());
        when(sheetsReader.readCourses()).thenReturn(List.of());
        when(sheetsReader.readRequirementCourses()).thenReturn(List.of(
                new RequirementCourseRow(departmentName, "", "전문실기", "2019", "1", "0"),
                new RequirementCourseRow(departmentName, "", "맨손체조", "2019", "1", "0")));

        sync(SYNC_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requirementCourse.applied").value(2))
                .andExpect(jsonPath("$.data.errors").isEmpty());

        Department department = departmentRepository.findAll().stream()
                .filter(d -> d.getName().equals(departmentName))
                .findFirst().orElseThrow();
        List<RequirementCourse> saved = requirementCourseRepository.findAll().stream()
                .filter(rc -> rc.getDepartment().getId().equals(department.getId()))
                .toList();
        assertThat(saved).extracting(RequirementCourse::getName)
                .containsExactlyInAnyOrder("전문실기", "맨손체조");

        // 같은 시트로 다시 동기화해도 예외 없이 그대로 2건이어야 한다(업데이트, 중복 생성 아님).
        sync(SYNC_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requirementCourse.applied").value(2));

        List<RequirementCourse> savedAgain = requirementCourseRepository.findAll().stream()
                .filter(rc -> rc.getDepartment().getId().equals(department.getId()))
                .toList();
        assertThat(savedAgain).hasSize(2);
    }

    @Test
    void 이미_존재하는_학수번호를_다시_동기화하면_새로_만들지_않고_기존_행을_갱신한다() throws Exception {
        String suffix = "동기화3";
        String schoolName = "동기화테스트대학교-" + suffix;

        when(sheetsReader.readSchools()).thenReturn(List.of(new SchoolRow(schoolName)));
        when(sheetsReader.readDepartments()).thenReturn(List.of());
        when(sheetsReader.readDivisions()).thenReturn(List.of());
        when(sheetsReader.readRequirementCourses()).thenReturn(List.of());
        when(sheetsReader.readCourses()).thenReturn(List.of(new CourseRow(
                schoolName, "SYNC301", "구이름", "3", "", "", "", "", "", "BOTH", "false", "true", "2026")));

        sync(SYNC_TOKEN).andExpect(status().isOk());
        assertThat(courseRepository.findByCourseCode("SYNC301").orElseThrow().getName()).isEqualTo("구이름");

        when(sheetsReader.readCourses()).thenReturn(List.of(new CourseRow(
                schoolName, "SYNC301", "새이름", "4", "", "", "", "", "", "BOTH", "false", "false", "2026")));
        sync(SYNC_TOKEN).andExpect(status().isOk());

        List<Course> withCode = courseRepository.findAll().stream()
                .filter(c -> "SYNC301".equals(c.getCourseCode())).toList();
        assertThat(withCode).hasSize(1);
        Course updated = withCode.get(0);
        assertThat(updated.getName()).isEqualTo("새이름");
        assertThat(updated.getCredit()).isEqualTo(4);
        assertThat(updated.isActive()).isFalse();
    }

    // 회귀 테스트: syncCourses가 행마다 courseRepository.findByCourseCode()를 날리던 N+1을
    // preloadCourses(school당 findBySchool 1회)로 고쳤다. 이미 존재하는 과목을 다시 동기화할 때
    // (=매 행이 UPDATE 경로) 걸리는 쿼리 수 증가폭이 행 수에 비례해 2배(SELECT+UPDATE)가 아니라
    // 거의 1배(UPDATE만)여야 한다. 로컬 MySQL 8,317건 동기화가 191~198초 걸리던 게 이 버그였다.
    @Test
    void 기존_과목_재동기화는_행_개수에_비례해서_조회_쿼리가_늘지_않는다() throws Exception {
        long queriesFor5 = measureExistingCourseUpdateQueryCount(5, "쿼리수1");
        long queriesFor50 = measureExistingCourseUpdateQueryCount(50, "쿼리수2");
        long delta = queriesFor50 - queriesFor5;
        System.out.printf("%n=== 쿼리 수: 과목 5건=%d, 과목 50건=%d (증가폭=%d) ===%n%n",
                queriesFor5, queriesFor50, delta);

        // 행마다 SELECT 1 + UPDATE 1이 걸리던 예전 코드라면 45건 차이가 ~90 늘어난다.
        // preloadCourses로 고친 뒤에는 school당 SELECT 1회뿐이라 ~45(UPDATE만)여야 한다.
        assertThat(delta).isLessThan(70);
    }

    private long measureExistingCourseUpdateQueryCount(int courseCount, String suffix) throws Exception {
        String schoolName = "동기화테스트대학교-" + suffix;
        School school = schoolRepository.save(School.builder().name(schoolName).build());
        Department department = departmentRepository.save(
                Department.builder().school(school).college("공과대학").name("학과-" + suffix).build());

        List<CourseRow> sheetRows = new ArrayList<>();
        for (int i = 0; i < courseCount; i++) {
            String code = "Q" + suffix + i;
            courseRepository.save(Course.builder()
                    .school(school).courseCode(code).name("원래" + i).credit(3)
                    .offeringDepartment(department).openedSemester(OpenedSemester.BOTH)
                    .isEnglish(false).isSw(false).isActive(true).build());
            sheetRows.add(new CourseRow(
                    schoolName, code, "갱신됨" + i, "4", "", "", "", "", "", "BOTH", "false", "true", "2026"));
        }

        when(sheetsReader.readSchools()).thenReturn(List.of());
        when(sheetsReader.readDepartments()).thenReturn(List.of());
        when(sheetsReader.readDivisions()).thenReturn(List.of());
        when(sheetsReader.readRequirementCourses()).thenReturn(List.of());
        when(sheetsReader.readCourses()).thenReturn(sheetRows);

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        sync(SYNC_TOKEN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.course.applied").value(courseCount));

        return statistics.getPrepareStatementCount();
    }

    private ResultActions sync(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/admin/seed-data/sync").header("X-Admin-Sync-Token", token));
    }
}
