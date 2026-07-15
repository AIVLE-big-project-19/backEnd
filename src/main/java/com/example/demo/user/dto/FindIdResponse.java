package com.example.demo.user.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class FindIdResponse {

    private String loginId;

    private String maskedLoginId;

    private LocalDateTime createdAt;
}
