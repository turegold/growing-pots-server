package com.growingpots.domain.admin.controller;

import com.growingpots.domain.admin.dto.response.SeedDataSyncResponse;
import com.growingpots.domain.admin.service.SeedDataSyncService;
import com.growingpots.global.exception.BaseException;
import com.growingpots.global.response.BaseResponse;
import com.growingpots.global.response.error.ErrorCode;
import com.growingpots.global.response.success.SuccessCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "[관리자] 시드 데이터 동기화", description = "구글시트 기반 과목/학과 등 마스터 데이터 반영")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/seed-data")
public class SeedDataSyncController {

    private final SeedDataSyncService seedDataSyncService;

    @Value("${admin.sync-token}")
    private String adminSyncToken;

    @Operation(summary = "구글시트 -> DB 동기화", description = "School/Department/Division/Course/RequirementCourse "
            + "시트를 읽어 검증 후 반영한다. 관리자 전용 토큰(X-Admin-Sync-Token 헤더)이 필요하다.")
    @PostMapping("/sync")
    public BaseResponse<SeedDataSyncResponse> sync(
            @RequestHeader("X-Admin-Sync-Token") String token
    ) throws IOException {
        if (adminSyncToken.isBlank() || !adminSyncToken.equals(token)) {
            throw new BaseException(ErrorCode.UNAUTHORIZED);
        }
        SeedDataSyncResponse response = seedDataSyncService.sync();
        return BaseResponse.success(SuccessCode.OK, response);
    }
}
