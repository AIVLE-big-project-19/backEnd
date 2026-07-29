package com.example.demo.test.controller;

import com.example.demo.test.dto.AnalysisRequest;
import com.example.demo.test.entity.AnalysisResult;
import com.example.demo.test.repository.AnalysisRepository;
import com.example.demo.test.service.AnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final AnalysisService analysisService;
    private final AnalysisRepository repository;


    @PostMapping
    public ResponseEntity<Map<String, Object>> startAnalysis(@RequestBody AnalysisRequest req) {
        // 1. DB에 진행중 상태 기록 후 ID 발급
        Long taskId = analysisService.createAnalysisTask(req.getUserId(), req.getImageUrl());

        // 2. 백그라운드 비동기 처리 시작 (즉시 리턴됨)
        analysisService.runAnalysisPipeline(taskId, req.getImageUrl());

        // 3. React에게 Task ID 전달
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "IN_PROGRESS"));
    }

    // React에서 주기적으로 상태/결과 확인(Polling)용 API
    @GetMapping("/{taskId}")
    public ResponseEntity<AnalysisResult> getAnalysisResult(@PathVariable Long taskId) {
        AnalysisResult result = repository.findById(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok(result);
    }
}