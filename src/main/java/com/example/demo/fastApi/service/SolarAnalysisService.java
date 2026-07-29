package com.example.demo.fastApi.service;

import com.example.demo.fastApi.entity.SolarAnalysis;
import com.example.demo.fastApi.dto.PredictionResponseDto;
import com.example.demo.fastApi.repository.SolarAnalysisRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SolarAnalysisService {

    private final SolarAnalysisRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PredictionResponseDto analyzeAndSave(MultipartFile imageFile, String extent3857) throws IOException {
        RestTemplate restTemplate = new RestTemplate();
        String fastApiUrl = "http://localhost:8000/predict";

        // 1. 헤더 및 바운더리 설정
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        ByteArrayResource fileResource = new ByteArrayResource(imageFile.getBytes()) {
            @Override
            public String getFilename() {
                String originalFilename = imageFile.getOriginalFilename();
                return (originalFilename != null && !originalFilename.isBlank()) ? originalFilename : "map_image.png";
            }
        };

        body.add("image", fileResource);
        body.add("extent3857", extent3857);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        // 2. FastAPI 호출 (단일 객체로 변경)
        ResponseEntity<PredictionResponseDto> response = restTemplate.postForEntity(
                fastApiUrl, requestEntity, PredictionResponseDto.class
        );

        PredictionResponseDto resultDto = response.getBody();

        if (resultDto != null && resultDto.getPredictions() != null) {
            // 3. JPA DB 저장
            for (PredictionResponseDto.PredictionDto pred : resultDto.getPredictions()) {
                SolarAnalysis entity = SolarAnalysis.builder()
                        .candidateType(pred.getCandidateType())
                        .confidence(pred.getConfidence())
                        .polygonJson(objectMapper.writeValueAsString(pred.getPolygon()))
                        .pixelArea(pred.getPixelArea())
                        .realArea(pred.getRealArea())
                        .modelVersion(pred.getModelVersion())
                        .build();
                repository.save(entity);
            }
        }

        // 4. React로 전체 결과(predictions + annotated_image) 전달
        return resultDto;
    }
}