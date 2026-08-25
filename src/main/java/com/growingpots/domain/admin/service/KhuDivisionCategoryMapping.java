package com.growingpots.domain.admin.service;

import com.growingpots.domain.university.entity.enums.DivisionCategory;
import java.util.Map;
import java.util.Optional;

// 경희대 수강신청 시스템의 원본 이수구분명(22개) -> Growing Pots의 DivisionCategory(7개) 매핑.
// 두 체계가 1:1로 안 맞아서, 아직 기획상 세분화하지 않은 항목은 전부 GENERAL_ELECTIVE로 뭉뚱그리고,
// 이수구분이라 보기 애매한 특수 학사 항목(선수과목/논문지도/종합시험 등)은 매핑하지 않는다
// (Course.defaultDivision을 null로 남겨 과목 검색·추천에 엉뚱하게 노출되지 않게 함).
public final class KhuDivisionCategoryMapping {

    private static final String GENERAL_ELECTIVE_PREFIX = "일반선택"; // "일반선택[배움학점제,군사학,...]" 각주 포함

    private static final Map<String, DivisionCategory> MAPPING = Map.ofEntries(
            Map.entry("전공필수", DivisionCategory.MAJOR_REQUIRED),
            Map.entry("전공선택", DivisionCategory.MAJOR_ELECTIVE),
            Map.entry("전공기초", DivisionCategory.MAJOR_BASIC),
            Map.entry("배분이수교과", DivisionCategory.DISTRIBUTED_GE),
            Map.entry("자유이수", DivisionCategory.FREE_GE),
            Map.entry("계절학기전공필수", DivisionCategory.MAJOR_REQUIRED),
            Map.entry("계절학기전공기초", DivisionCategory.MAJOR_BASIC),
            // 실제 학점을 주는 과목이지만 아직 전용 카테고리가 기획되지 않은 것들 - GENERAL_ELECTIVE로.
            Map.entry("교직과", DivisionCategory.GENERAL_ELECTIVE),
            Map.entry("공통과목", DivisionCategory.GENERAL_ELECTIVE),
            Map.entry("전공공통", DivisionCategory.GENERAL_ELECTIVE),
            Map.entry("공통필수", DivisionCategory.GENERAL_ELECTIVE),
            Map.entry("중핵교과", DivisionCategory.GENERAL_ELECTIVE),
            Map.entry("기초교과", DivisionCategory.GENERAL_ELECTIVE),
            Map.entry("교직전선", DivisionCategory.GENERAL_ELECTIVE)
    );

    // 매핑에 없으면(구분없음/선수과목/논문지도과목/종합시험/외국어대체/논문대체/과정구분없음 등)
    // 의도적으로 빈 값 - 호출부는 Course.defaultDivision을 null로 둔다.
    public static Optional<DivisionCategory> resolve(String categoryName) {
        if (categoryName.startsWith(GENERAL_ELECTIVE_PREFIX)) {
            return Optional.of(DivisionCategory.GENERAL_ELECTIVE);
        }
        return Optional.ofNullable(MAPPING.get(categoryName));
    }

    private KhuDivisionCategoryMapping() {
    }
}
