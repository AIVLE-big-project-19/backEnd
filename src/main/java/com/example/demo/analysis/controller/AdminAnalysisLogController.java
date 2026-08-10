package com.example.demo.analysis.controller;

import com.example.demo.analysis.service.AnalysisSnapshotService;
import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/analysis-logs")
public class AdminAnalysisLogController {

    private final AnalysisSnapshotService analysisSnapshotService;

    @GetMapping
    public ApiResponse<List<AnalysisSnapshotService.AdminAnalysisLogItem>> logs() {
        return ApiResponse.success(SuccessCode.BOARD_LIST_FOUND, analysisSnapshotService.adminHistory());
    }
}
