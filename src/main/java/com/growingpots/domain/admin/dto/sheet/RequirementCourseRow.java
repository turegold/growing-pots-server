package com.growingpots.domain.admin.dto.sheet;

public record RequirementCourseRow(
        String department,
        String division,
        String name,
        String baseYear,
        String minCount,
        String minCredit
) {
}
