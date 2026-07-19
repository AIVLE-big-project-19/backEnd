package com.example.demo.user.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.user.dto.ConsentStatusResponse;
import com.example.demo.user.dto.MarketingConsentRequest;
import com.example.demo.user.service.ConsentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users/me/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService consentService;

    @GetMapping
    public ApiResponse<ConsentStatusResponse> getMyConsents(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(
                SuccessCode.CONSENT_STATUS_FOUND,
                consentService.getConsentStatus(userId)
        );
    }

    @PutMapping("/marketing")
    public ApiResponse<ConsentStatusResponse.ConsentItem> updateMarketingConsent(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody MarketingConsentRequest request
    ) {
        return ApiResponse.success(
                SuccessCode.MARKETING_CONSENT_UPDATED,
                consentService.updateMarketingConsent(userId, request.getAgreed())
        );
    }
}
