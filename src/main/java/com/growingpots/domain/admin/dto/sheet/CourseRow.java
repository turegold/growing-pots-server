package com.growingpots.domain.admin.dto.sheet;

public record CourseRow(
        String school,
        String courseCode,
        String name,
        String credit,
        String offeringDepartment,
        String defaultDivision,
        String geArea,
        String recommendedYearLow,
        String recommendedYearHigh,
        String openedSemester,
        String isEnglish,
        String isActive,
        String lastSeenYear
) {
}
