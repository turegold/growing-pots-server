package com.growingpots.domain.graduation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growingpots.domain.transcript.entity.GraduationAnalysisSummary;
import com.growingpots.domain.transcript.entity.StudentCourse;
import com.growingpots.domain.transcript.entity.enums.CourseStatus;
import com.growingpots.domain.transcript.entity.enums.RecordSource;
import com.growingpots.domain.transcript.entity.enums.Semester;
import com.growingpots.domain.transcript.repository.GraduationAnalysisSummaryRepository;
import com.growingpots.domain.transcript.repository.StudentCourseRepository;
import com.growingpots.domain.university.entity.Course;
import com.growingpots.domain.university.entity.Department;
import com.growingpots.domain.university.entity.RequirementCourse;
import com.growingpots.domain.university.entity.School;
import com.growingpots.domain.university.entity.enums.OpenedSemester;
import com.growingpots.domain.university.repository.CourseRepository;
import com.growingpots.domain.university.repository.DepartmentRepository;
import com.growingpots.domain.university.repository.RequirementCourseItemRepository;
import com.growingpots.domain.university.repository.RequirementCourseRepository;
import com.growingpots.domain.university.repository.SchoolRepository;
import com.growingpots.domain.university.entity.RequirementCourseItem;
import com.growingpots.domain.user.entity.Member;
import com.growingpots.domain.user.entity.StudentMajor;
import com.growingpots.domain.user.entity.StudentMajor.MajorType;
import com.growingpots.domain.user.entity.StudentProfile;
import com.growingpots.domain.user.entity.enums.OauthProvider;
import com.growingpots.domain.user.repository.MemberRepository;
import com.growingpots.domain.user.repository.StudentMajorRepository;
import com.growingpots.domain.user.repository.StudentProfileRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.Collections;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// getGraduation()이 전공 개수에 비례해서 쿼리를 반복 발사하지 않는지 검증한다 (N+1 회귀 방지).
// "01. 졸업요건 조회 API N+1 제거.md" 참고 — 전공마다 학과 독립 졸업요건(division=null인
// RequirementCourse, 문서의 "학과 졸업필수 요건 있음" 케이스)까지 갖춰야 배치 조회 경로가 전부 걸린다.
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class GraduationQueryCountTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private SchoolRepository schoolRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private MemberRepository memberRepository;
    @Autowired private StudentProfileRepository studentProfileRepository;
    @Autowired private StudentMajorRepository studentMajorRepository;
    @Autowired private GraduationAnalysisSummaryRepository graduationAnalysisSummaryRepository;
    @Autowired private RequirementCourseRepository requirementCourseRepository;
    @Autowired private RequirementCourseItemRepository requirementCourseItemRepository;
    @Autowired private CourseRepository courseRepository;
    @Autowired private StudentCourseRepository studentCourseRepository;

    // 전공이 1개에서 3개로 늘어도(+2), 늘어나는 쿼리 수는 전공당 1개(+2) 이하여야 한다.
    // 전공별로 요약/졸업필수요건/요건과목/이수내역/자격증을 매번 다시 조회하면 전공당 5개씩 늘어
    // 차이가 훨씬 크게 벌어진다.
    @Test
    void 졸업현황_조회는_전공_개수에_비례해서_쿼리가_늘지_않는다() throws Exception {
        long queriesFor1Major = measureQueryCount(1, "9505");
        long queriesFor3Majors = measureQueryCount(3, "9506");
        System.out.printf("%n=== 쿼리 수: 전공 1개=%d, 전공 3개=%d ===%n%n", queriesFor1Major, queriesFor3Majors);

        assertThat(queriesFor3Majors - queriesFor1Major).isLessThanOrEqualTo(2);
    }

    private long measureQueryCount(int majorCount, String suffix) throws Exception {
        School school = schoolRepository.save(School.builder().name("경희대학교-" + suffix).build());

        StudentProfile profile = null;
        Member member = memberRepository.save(Member.builder()
                .nickname("쿼리수테스트").oauthProvider(OauthProvider.KAKAO)
                .oauthId(suffix).email(null).build());

        for (int i = 0; i < majorCount; i++) {
            Department department = departmentRepository.save(Department.builder()
                    .school(school).college("공과대학").name("학과" + suffix + "_" + i).build());

            if (i == 0) {
                profile = studentProfileRepository.save(StudentProfile.builder()
                        .member(member).school(school).department(department).admissionYear(2024).build());
            }

            StudentMajor major = studentMajorRepository.save(StudentMajor.builder()
                    .studentProfile(profile).department(department)
                    .majorType(i == 0 ? MajorType.MAIN : MajorType.DOUBLE).build());

            graduationAnalysisSummaryRepository.save(
                    GraduationAnalysisSummary.builder().studentMajor(major).build());

            // 학과 독립 졸업요건(division=null) — "학과 졸업필수 요건 있음" 케이스를 재현해
            // 요건/요건과목 배치 조회 경로까지 실제로 타게 한다.
            RequirementCourse requirementCourse = requirementCourseRepository.save(RequirementCourse.builder()
                    .department(department).division(null).name("졸업필수" + i).baseYear(2020).minCount(1).build());
            Course course = courseRepository.save(Course.builder()
                    .school(school).courseCode("REQ" + suffix + i).name("졸업필수과목" + i).credit(3)
                    .offeringDepartment(department).openedSemester(OpenedSemester.BOTH)
                    .isEnglish(false).isSw(false).isActive(true).build());
            requirementCourseItemRepository.save(RequirementCourseItem.builder()
                    .requirementCourse(requirementCourse).course(course).build());

            studentCourseRepository.save(StudentCourse.builder()
                    .studentProfile(profile).course(course)
                    .rawCourseCode(course.getCourseCode()).rawCourseName(course.getName())
                    .credit(3).takenYear(2024).takenSemester(Semester.FIRST)
                    .status(CourseStatus.COMPLETED).source(RecordSource.PDF).isRetake(false).build());
        }

        Authentication auth = new UsernamePasswordAuthenticationToken(
                member.getId().toString(), null, Collections.emptyList());

        SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
        Statistics statistics = sessionFactory.getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        mockMvc.perform(get("/api/v1/students/me/graduation").with(authentication(auth)))
                .andExpect(status().isOk());

        return statistics.getPrepareStatementCount();
    }
}
