package com.example.demo.notification.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<NotificationService.NotificationSummary> getNotifications() {
        return ApiResponse.success(com.example.demo.global.response.SuccessCode.BOARD_LIST_FOUND,
                notificationService.getNotifications(currentUserId()));
    }

    @PatchMapping("/{notificationId}/read")
    public ApiResponse<Void> markRead(@PathVariable Long notificationId) {
        notificationService.markRead(notificationId, currentUserId());
        return ApiResponse.success(com.example.demo.global.response.SuccessCode.BOARD_FOUND);
    }

    @PatchMapping("/read-all")
    public ApiResponse<Void> markAllRead() {
        notificationService.markAllRead(currentUserId());
        return ApiResponse.success(com.example.demo.global.response.SuccessCode.BOARD_FOUND);
    }

    @DeleteMapping("/{notificationId}")
    public ApiResponse<Void> delete(@PathVariable Long notificationId) {
        notificationService.delete(notificationId, currentUserId());
        return ApiResponse.success(com.example.demo.global.response.SuccessCode.BOARD_FOUND);
    }

    @DeleteMapping
    public ApiResponse<Void> deleteAll() {
        notificationService.deleteAll(currentUserId());
        return ApiResponse.success(com.example.demo.global.response.SuccessCode.BOARD_FOUND);
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }
}
