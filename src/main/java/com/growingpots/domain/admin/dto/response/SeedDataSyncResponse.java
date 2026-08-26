package com.growingpots.domain.admin.dto.response;

import java.util.List;

public record SeedDataSyncResponse(
        TableSyncResult school,
        TableSyncResult department,
        TableSyncResult division,
        TableSyncResult course,
        TableSyncResult requirementCourse,
        List<SyncError> errors
) {
}
