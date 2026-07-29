package com.example.demo.fastApi.controller;

import com.example.demo.fastApi.dto.PredictionResponseDto;
import com.example.demo.fastApi.service.SolarAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/v1/solar")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // React 연동 허용
public class SolarAnalysisController {

    private final SolarAnalysisService solarAnalysisService;

    @PostMapping("/analyze")
    public ResponseEntity<PredictionResponseDto> analyzeImage(
            @RequestParam("image") MultipartFile image,
            @RequestParam("extent3857") String extent3857
    ) throws IOException {
        // Service에서 단일 PredictionResponseDto(predictions + annotatedImage)를 반환하도록 변경됨
        PredictionResponseDto result = solarAnalysisService.analyzeAndSave(image, extent3857);
        return ResponseEntity.ok(result);
    }
}