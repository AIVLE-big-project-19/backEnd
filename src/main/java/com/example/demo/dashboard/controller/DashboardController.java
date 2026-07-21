package com.example.demo.dashboard.controller;

import com.example.demo.dashboard.dto.SiteAnalysisRequest;
import com.example.demo.dashboard.dto.SiteAnalysisResponse;
import com.example.demo.dashboard.service.DashboardService;
import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
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

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }
}
