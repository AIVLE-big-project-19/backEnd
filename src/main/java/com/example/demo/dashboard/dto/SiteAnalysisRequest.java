package com.example.demo.dashboard.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

public record SiteAnalysisRequest(
        @NotBlank String address,
        Double latitude,
        Double longitude,
        @DecimalMin(value = "1.0", message = "면적은 1㎡ 이상이어야 합니다.") Double areaM2,
        @DecimalMin(value = "0.1", message = "설치 용량은 0.1kW 이상이어야 합니다.") Double capacityKw
) {}
