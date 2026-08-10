package com.example.demo.analysis.controller;

import com.example.demo.analysis.service.AnalysisSnapshotService;
import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/analysis-history")
public class AnalysisHistoryController {

    private final AnalysisSnapshotService analysisSnapshotService;

    @GetMapping
    public ApiResponse<List<AnalysisSnapshotService.AnalysisHistoryItem>> history() {
        return ApiResponse.success(SuccessCode.BOARD_LIST_FOUND, analysisSnapshotService.history(currentUserId()));
    }

    @PatchMapping("/{analysisId}")
    public ApiResponse<Void> update(
            @PathVariable Long analysisId,
            @RequestBody ManagementRequest request
    ) {
        analysisSnapshotService.updateManagement(analysisId, currentUserId(), request.favorite(), request.status());
        return ApiResponse.success(SuccessCode.BOARD_FOUND);
    }

    @DeleteMapping("/{analysisId}")
    public ApiResponse<Void> delete(@PathVariable Long analysisId) {
        analysisSnapshotService.delete(analysisId, currentUserId());
        return ApiResponse.success(SuccessCode.BOARD_FOUND);
    }

    @DeleteMapping
    public ApiResponse<Void> deleteSelected(@RequestBody DeleteRequest request) {
        analysisSnapshotService.deleteSelected(request.analysisIds(), currentUserId());
        return ApiResponse.success(SuccessCode.BOARD_FOUND);
    }

    @DeleteMapping("/all")
    public ApiResponse<Void> deleteAll() {
        analysisSnapshotService.deleteAll(currentUserId());
        return ApiResponse.success(SuccessCode.BOARD_FOUND);
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }

    public record ManagementRequest(boolean favorite, String status) {}

    public record DeleteRequest(List<Long> analysisIds) {}
}
