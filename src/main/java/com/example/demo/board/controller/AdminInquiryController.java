package com.example.demo.board.controller;

import com.example.demo.board.dto.BoardResponse;
import com.example.demo.board.service.BoardService;
import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/inquiries")
@RequiredArgsConstructor
public class AdminInquiryController {

    private final BoardService boardService;

    @GetMapping("/unanswered")
    public ApiResponse<List<BoardResponse>> getUnanswered() {
        return ApiResponse.success(SuccessCode.ADMIN_INQUIRY_UNANSWERED_FOUND, boardService.getUnansweredInquiries());
    }

    @GetMapping("/unanswered-count")
    public ApiResponse<Long> getUnansweredCount() {
        return ApiResponse.success(SuccessCode.ADMIN_INQUIRY_UNANSWERED_FOUND, boardService.countUnansweredInquiries());
    }
}
