package com.example.demo.user.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.user.dto.AdminUserResponse;
import com.example.demo.user.dto.RoleChangeRequest;
import com.example.demo.user.service.AdminUserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public ApiResponse<List<AdminUserResponse>> getUsers() {
        return ApiResponse.success(SuccessCode.ADMIN_USER_LIST_FOUND, adminUserService.getUsers());
    }

    @PatchMapping("/{userId}/role")
    public ApiResponse<AdminUserResponse> changeRole(
            @PathVariable Long userId,
            @Valid @RequestBody RoleChangeRequest request
    ) {
        return ApiResponse.success(
                SuccessCode.ADMIN_USER_ROLE_UPDATED,
                adminUserService.changeRole(userId, request.getRole())
        );
    }
}
