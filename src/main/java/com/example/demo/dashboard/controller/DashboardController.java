package com.example.demo.dashboard.controller;

import com.example.demo.dashboard.dto.DashboardCandidateAnalysisResponse;
import com.example.demo.dashboard.service.DashboardCandidateAnalysisService;
import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.PageResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.idleland.dto.IdleLandSearchResultDto;
import com.example.demo.idleland.service.IdleLandSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final IdleLandSearchService idleLandSearchService;
    private final DashboardCandidateAnalysisService dashboardCandidateAnalysisService;

    @GetMapping("/candidates/{idleLandId}/analysis")
    public ApiResponse<DashboardCandidateAnalysisResponse> candidateAnalysis(@PathVariable Long idleLandId) {
        return ApiResponse.success(
                SuccessCode.BOARD_FOUND,
                dashboardCandidateAnalysisService.analyze(idleLandId, currentUserId())
        );
    }

    @GetMapping("/candidates/regions")
    public ApiResponse<PageResponse<IdleLandSearchResultDto>> candidatesByRegion(
            @RequestParam String sido,
            @RequestParam String sigungu,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(
                SuccessCode.IDLE_LAND_SEARCH_FOUND,
                PageResponse.from(idleLandSearchService.findByRegionScored(sido, sigungu, page, size))
        );
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }
}
