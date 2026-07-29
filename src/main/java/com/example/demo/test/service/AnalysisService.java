package com.example.demo.test.service;

import com.example.demo.test.entity.AnalysisResult;
import com.example.demo.test.repository.AnalysisRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final AnalysisRepository repository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${vision.ai.url}")
    private String visionAiUrl;

    // 1. 작업 등록
    @Transactional
    public Long createAnalysisTask(Long userId, String imageUrl) {
        AnalysisResult entity = new AnalysisResult();
        entity.setUserId(userId);
        entity.setImageUrl(imageUrl);
        entity.setStatus(AnalysisResult.AnalysisStatus.IN_PROGRESS);

        AnalysisResult saved = repository.save(entity);
        return saved.getId();
    }

    // 2. 비동기로 Vision AI 및 ML 파이프라인 수행
    @Async
    @Transactional
    public void runAnalysisPipeline(Long taskId, String imageUrl) {
        try {
            log.info("비동기 분석 시작 - TaskID: {}, ImageUrl: {}", taskId, imageUrl);

            // Step A: FastAPI 규격에 정확히 맞춘 Request Payload 생성
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("task_id", taskId);              // 💡 target_id -> task_id 변경
            requestBody.put("image_url", imageUrl);          // 💡 image_url
            requestBody.put("analysis_type", "SHADE");       // 💡 누락되었던 필수 필드 추가

            // FastAPI 응답 수신
            Map<String, Object> visionResult = restTemplate.postForObject(
                    visionAiUrl, requestBody, Map.class
            );

            log.info("FastAPI 응답 결과: {}", visionResult);

            // Step B: DB 엔티티 가져오기
            AnalysisResult result = repository.findById(taskId)
                    .orElseThrow(() -> new RuntimeException("Task Not Found: " + taskId));

            if (visionResult != null) {
                // 💡 안전한 형변환 (Double.valueOf 사용)
                Double shadeRatio = parseToDouble(visionResult.get("shade_ratio"));
                Double sunlightHours = parseToDouble(visionResult.get("sunlight_hours"));
                Double usableAreaSqm = parseToDouble(visionResult.get("usable_area_sqm"));

                // Vision AI 결과 세팅
                result.setShadeRatio(shadeRatio);
                result.setSunlightHours(sunlightHours);
                result.setUsableAreaSqm(usableAreaSqm);

                // Step C: ML 적합 선정 처리
                if (shadeRatio != null && shadeRatio < 0.2) {
                    result.setRecommendedModel("고효율-A타입");
                    result.setSuitabilityScore(95.0);
                } else {
                    result.setRecommendedModel("일반-B타입");
                    result.setSuitabilityScore(78.0);
                }

                // Step D: 상태 완료로 업데이트
                result.setStatus(AnalysisResult.AnalysisStatus.COMPLETED);
                log.info("분석 완료 성공! TaskID: {}", taskId);
            } else {
                result.setStatus(AnalysisResult.AnalysisStatus.FAILED);
            }

        } catch (Exception e) {
            log.error("Analysis Pipeline 실패 - TaskID: {}", taskId, e);

            AnalysisResult result = repository.findById(taskId).orElse(null);
            if (result != null) {
                result.setStatus(AnalysisResult.AnalysisStatus.FAILED);
            }
        }
    }

    // Any Number -> Double 안전 변환 헬퍼 메서드
    private Double parseToDouble(Object value) {
        if (value == null) return null;
        try {
            return Double.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}