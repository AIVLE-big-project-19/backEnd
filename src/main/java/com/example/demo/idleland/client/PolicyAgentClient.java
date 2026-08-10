package com.example.demo.idleland.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.report.dto.AiAnalysisResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;


@Slf4j
@Component
public class PolicyAgentClient {

    @Value("${policy.agent.base-url}")
    private String baseUrl;

    @Value("${policy.agent.internal-api-key}")
    private String internalApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public Map<String, Object> analyze(AiAnalysisResponse candidate) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (internalApiKey != null && !internalApiKey.isBlank()) {
            headers.set("X-Internal-API-Key", internalApiKey);
        }

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    baseUrl + "/api/v1/agent/analyze",
                    HttpMethod.POST,
                    new HttpEntity<>(candidate, headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    }
            );
            Map<String, Object> result = response.getBody();
            if (result == null) {
                throw new CustomException(ErrorCode.POLICY_AGENT_REQUEST_FAILED, "정책 Agent가 빈 응답을 반환했습니다.");
            }
            return result;
        } catch (CustomException e) {
            throw e;
        } catch (RestClientException e) {
            log.warn("정책 Agent 호출 실패: {}", e.getMessage());
            throw new CustomException(ErrorCode.POLICY_AGENT_REQUEST_FAILED, "정책 Agent 호출 실패: " + e.getMessage());
        }
    }
}
