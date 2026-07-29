package com.example.demo.dashboard.controller;

import com.example.demo.dashboard.dto.DashboardCandidateAnalysisResponse;
import com.example.demo.dashboard.dto.SiteAnalysisRequest;
import com.example.demo.dashboard.dto.SiteAnalysisResponse;
import com.example.demo.dashboard.service.DashboardCandidateAnalysisService;
import com.example.demo.dashboard.service.DashboardService;
import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.PageResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.idleland.dto.IdleLandSearchResultDto;
import com.example.demo.idleland.service.IdleLandSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final DashboardService dashboardService;
    private final IdleLandSearchService idleLandSearchService;
    private final DashboardCandidateAnalysisService dashboardCandidateAnalysisService;

    @GetMapping("/candidates")
    public ApiResponse<List<IdleLandSearchResultDto>> candidates(
            @RequestParam(value = "q", required = false) String query
    ) {
        List<IdleLandSearchResultDto> candidates = query == null || query.isBlank()
                ? idleLandSearchService.findAllScored()
                : idleLandSearchService.search(query);
        return ApiResponse.success(SuccessCode.IDLE_LAND_SEARCH_FOUND, candidates);
    }

    @GetMapping("/candidates/{idleLandId}/analysis")
    public ApiResponse<DashboardCandidateAnalysisResponse> candidateAnalysis(@PathVariable Long idleLandId) {
        return ApiResponse.success(
                SuccessCode.BOARD_FOUND,
                dashboardCandidateAnalysisService.analyze(idleLandId)
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

    @PostMapping("/analyses")
    public ApiResponse<SiteAnalysisResponse> analyze(@Valid @RequestBody SiteAnalysisRequest request) {
        return ApiResponse.success(SuccessCode.BOARD_FOUND, dashboardService.analyze(currentUserId(), request));
    }

    @GetMapping("/analyses/me")
    public ApiResponse<List<SiteAnalysisResponse>> history() {
        Long userId = currentUserId();
        if (userId == null) {
            return ApiResponse.fail("로그인 후 분석 이력을 조회할 수 있습니다.");
        }
        return ApiResponse.success(SuccessCode.BOARD_LIST_FOUND, dashboardService.history(userId));
    }

    @GetMapping("/analyses/demo")
    public ApiResponse<List<SiteAnalysisResponse>> demoAnalyses() {
        return ApiResponse.success(SuccessCode.BOARD_LIST_FOUND, dashboardService.demoAnalyses());
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }
}
