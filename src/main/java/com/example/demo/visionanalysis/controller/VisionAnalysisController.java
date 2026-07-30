package com.example.demo.visionanalysis.controller;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.visionanalysis.client.VisionAnalysisClient;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/vision-analysis")
@RequiredArgsConstructor
@Validated
public class VisionAnalysisController {

    private static final int MAX_CONCURRENT_REQUESTS = 5;

    private final VisionAnalysisClient visionAnalysisClient;
    private final Semaphore concurrencyLimiter = new Semaphore(MAX_CONCURRENT_REQUESTS);

    @PostMapping("/csv")
    public ResponseEntity<byte[]> downloadCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "3") @Min(1) @Max(20) int limit
    ) {
        if (!concurrencyLimiter.tryAcquire()) {
            throw new CustomException(ErrorCode.VISION_ANALYSIS_BUSY);
        }
        try {
            byte[] csv = visionAnalysisClient.fetchCsv(file, limit);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vision_analysis_result.csv\"");
            return new ResponseEntity<>(csv, headers, HttpStatus.OK);
        } finally {
            concurrencyLimiter.release();
        }
    }
}
