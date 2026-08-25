package com.growingpots.domain.admin.dto.response;

public record SyncError(String sheet, int row, String reason) {
}
