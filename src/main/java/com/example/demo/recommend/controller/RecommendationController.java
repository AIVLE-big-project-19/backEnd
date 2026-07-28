package com.example.demo.recommend.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.recommend.dto.RecommendationStatusResponse;
import com.example.demo.recommend.dto.RecommendationSubmitResponse;
import com.example.demo.recommend.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }
}
