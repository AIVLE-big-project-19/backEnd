package com.example.demo.user.dto;

import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AdminUserResponse {
    private Long id;
    private String loginId;
    private String email;
    private String name;
    private Provider provider;
    private Role role;
    private LocalDateTime createdAt;

    public static AdminUserResponse from(User user) {
        return AdminUserResponse.builder()
                .id(user.getId())
                .loginId(user.getLoginId())
                .email(user.getEmail())
                .name(user.getName())
                .provider(user.getProvider())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
