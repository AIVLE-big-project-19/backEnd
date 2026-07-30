package com.example.demo.visionanalysis.controller;

import com.example.demo.visionanalysis.client.VisionAnalysisClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/vision-analysis")
@RequiredArgsConstructor
public class VisionAnalysisController {

    private final VisionAnalysisClient visionAnalysisClient;

    @PostMapping("/csv")
    public ResponseEntity<byte[]> downloadCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "3") int limit
    ) {
        byte[] csv = visionAnalysisClient.fetchCsv(file, limit);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vision_analysis_result.csv\"");
        return new ResponseEntity<>(csv, headers, HttpStatus.OK);
    }
}
