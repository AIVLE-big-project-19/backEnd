package com.example.demo.recommend.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.recommend.client.dto.JobStatusResult;
import com.example.demo.recommend.client.dto.JobSubmitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Component
public class RecommendClient {

    private static final Logger log = LoggerFactory.getLogger(RecommendClient.class);

    private final RestClient restClient;
    private final String jobsPath;
    private final ObjectMapper objectMapper;

    public RecommendClient(
            @Qualifier("timeoutBoundRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${ai.server.base-url}") String baseUrl,
            @Value("${ai.server.recommend-jobs-path}") String jobsPath,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.jobsPath = jobsPath;
        this.objectMapper = objectMapper;
    }

    public JobSubmitResult submitJob(MultipartFile file, int limit) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());

        try {
            return restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(jobsPath).queryParam("limit", limit).build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(JobSubmitResult.class);
        } catch (RestClientResponseException e) {
            log.warn("AI 추천 job 등록 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.AI_RECOMMEND_FAILED, extractDetail(e).orElse(ErrorCode.AI_RECOMMEND_FAILED.getMessage()));
        } catch (RestClientException e) {
            log.warn("AI 추천 job 등록 요청 실패", e);
            throw new CustomException(ErrorCode.AI_RECOMMEND_FAILED);
        }
    }

    public JobStatusResult pollJob(String externalJobId) {
        try {
            return restClient.get()
                    .uri(jobsPath + "/{id}", externalJobId)
                    .retrieve()
                    .body(JobStatusResult.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientResponseException e) {
            log.warn("AI 추천 job 폴링 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.AI_RECOMMEND_FAILED, extractDetail(e).orElse(ErrorCode.AI_RECOMMEND_FAILED.getMessage()));
        } catch (RestClientException e) {
            log.warn("AI 추천 job 폴링 요청 실패", e);
            throw new CustomException(ErrorCode.AI_RECOMMEND_FAILED);
        }
    }

    private Optional<String> extractDetail(RestClientResponseException e) {
        try {
            JsonNode node = objectMapper.readTree(e.getResponseBodyAsString());
            JsonNode detail = node.get("detail");
            return (detail != null && !detail.isNull()) ? Optional.of(detail.asString()) : Optional.empty();
        } catch (RuntimeException parseError) {
            return Optional.empty();
        }
    }
}
