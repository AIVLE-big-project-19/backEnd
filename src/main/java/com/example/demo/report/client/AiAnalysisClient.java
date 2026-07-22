package com.example.demo.report.client;

import com.example.demo.report.dto.AiAnalysisResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AiAnalysisClient {

    private final RestClient restClient;
    private final String analyzePath;

    public AiAnalysisClient(RestClient.Builder restClientBuilder,
                             @Value("${ai.server.base-url}") String baseUrl,
                             @Value("${ai.server.analyze-path}") String analyzePath) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.analyzePath = analyzePath;
    }

    // AI 분석 서버 완료되면 메서드 수정 예정
    public AiAnalysisResponse fetchAnalysis(String address) {
        return restClient.get()
                .uri(analyzePath + "?address={address}", address)
                .retrieve()
                .body(AiAnalysisResponse.class);
    }
}
