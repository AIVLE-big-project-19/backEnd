package com.example.demo.user.dto;

import com.example.demo.user.entity.Provider;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class MyPageResponse {
    private String loginId;
    private String email;
    private String name;
    private Provider provider;
    private LocalDateTime createdAt;
}
