package com.example.demo.user.controller;

import com.example.demo.board.dto.BoardResponse;
import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.user.dto.ChangePasswordRequest;
import com.example.demo.user.dto.MyPageResponse;
import com.example.demo.user.dto.UpdateProfileRequest;
import com.example.demo.user.service.MyPageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users/me")
@RequiredArgsConstructor
public class MyPageController {
    private final MyPageService myPageService;

    @GetMapping
    public ApiResponse<MyPageResponse> getMyProfile(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(SuccessCode.MY_PAGE_FOUND, myPageService.getMyProfile(userId));
    }

    @PatchMapping
    public ApiResponse<MyPageResponse> updateMyProfile(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ApiResponse.success(
                SuccessCode.MY_PROFILE_UPDATED,
                myPageService.updateMyProfile(userId, request.getName())
        );
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        myPageService.changePassword(userId, request.getCurrentPassword(), request.getNewPassword());
        return ApiResponse.success(SuccessCode.MY_PASSWORD_CHANGED);
    }

    @GetMapping("/boards")
    public ApiResponse<List<BoardResponse>> getMyBoards(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(SuccessCode.MY_BOARD_LIST_FOUND, myPageService.getMyBoards(userId));
    }
}
