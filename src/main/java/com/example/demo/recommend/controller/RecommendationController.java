package com.example.demo.recommend.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.recommend.dto.RecommendationHistoryResponse;
import com.example.demo.recommend.dto.RecommendationStatusResponse;
import com.example.demo.recommend.dto.RecommendationSubmitResponse;
import com.example.demo.recommend.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendService recommendService;

    @PostMapping
    public ApiResponse<RecommendationSubmitResponse> submit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "3") int limit
    ) {
        RecommendationSubmitResponse response = recommendService.submit(file, limit, currentUserId());
        return ApiResponse.success(SuccessCode.RECOMMENDATION_SUBMITTED, response);
    }

    @GetMapping("/{id}")
    public ApiResponse<RecommendationStatusResponse> getStatus(@PathVariable Long id) {
        return ApiResponse.success(SuccessCode.RECOMMENDATION_STATUS_FOUND, recommendService.getStatus(id, currentUserId()));
    }

    @GetMapping("/me")
    public ApiResponse<List<RecommendationHistoryResponse>> history() {
        Long userId = currentUserId();
        if (userId == null) {
            return ApiResponse.fail("로그인 후 추천 이력을 조회할 수 있습니다.");
        }
        return ApiResponse.success(SuccessCode.RECOMMENDATION_HISTORY_FOUND, recommendService.getHistory(userId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        recommendService.delete(id, currentUserId());
        return ApiResponse.success(SuccessCode.RECOMMENDATION_DELETED);
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }
}
