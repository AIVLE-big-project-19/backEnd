package com.example.demo.user.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.user.dto.TermsResponse;
import com.example.demo.user.service.TermsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/terms")
@RequiredArgsConstructor
public class TermsController {

    private final TermsService termsService;

    @GetMapping("/{type}")
    public ApiResponse<TermsResponse> getTerms(@PathVariable String type) {
        return ApiResponse.success(SuccessCode.TERMS_FOUND, termsService.getTerms(type));
    }
}
