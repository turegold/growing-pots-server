package com.growingpots.domain.admin.dto.response;

public record TableSyncResult(int applied, int skipped, Integer deactivated) {

    public static TableSyncResult of(int applied, int skipped) {
        return new TableSyncResult(applied, skipped, null);
    }

    public static TableSyncResult ofCourse(int applied, int skipped, int deactivated) {
        return new TableSyncResult(applied, skipped, deactivated);
    }
}
