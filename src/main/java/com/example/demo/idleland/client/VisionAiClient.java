package com.example.demo.idleland.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class VisionAiClient {

    @Value("${fastapi.url}")
    private String visionAiBaseUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    @Getter
    @Setter
    public static class VisionPredictResponse {
        private List<Map<String, Object>> predictions;

        @JsonProperty("final_visualization_image")
        private String finalVisualizationImage;
    }

    public VisionPredictResponse predict(byte[] imageBytes, String extent3857) {
        log.info("Vision AI 호출: url={}, extent3857={}, imageBytes={}", visionAiBaseUrl, extent3857, imageBytes.length);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("image", new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return "candidate.png";
            }
        });
        body.add("extent3857", extent3857);

        try {
            ResponseEntity<VisionPredictResponse> response = restTemplate.postForEntity(
                    visionAiBaseUrl + "/predict",
                    new HttpEntity<>(body, headers),
                    VisionPredictResponse.class
            );
            VisionPredictResponse result = response.getBody();
            if (result == null) {
                throw new CustomException(ErrorCode.VISION_ANALYSIS_FAILED, "Vision AI가 빈 응답을 반환했습니다.");
            }
            log.info("Vision AI 응답: predictions={}", result.getPredictions());
            return result;
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(ErrorCode.VISION_ANALYSIS_FAILED, "Vision AI 호출 실패: " + e.getMessage());
        }
    }
}
