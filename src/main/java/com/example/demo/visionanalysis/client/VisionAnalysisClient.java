package com.example.demo.visionanalysis.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
public class VisionAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(VisionAnalysisClient.class);

    private final RestClient restClient;
    private final String visionCsvPath;
    private final ObjectMapper objectMapper;

    public VisionAnalysisClient(
            @Qualifier("visionCsvRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${ai.server.base-url}") String baseUrl,
            @Value("${ai.server.vision-csv-path}") String visionCsvPath,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .messageConverters(converters -> converters.stream()
                        .filter(FormHttpMessageConverter.class::isInstance)
                        .map(FormHttpMessageConverter.class::cast)
                        .forEach(converter -> converter.setMultipartCharset(StandardCharsets.UTF_8)))
                .build();
        this.visionCsvPath = visionCsvPath;
        this.objectMapper = objectMapper;
    }

    public byte[] fetchCsv(MultipartFile file, int limit) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());

        try {
            return restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(visionCsvPath).queryParam("limit", limit).build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            log.warn("비전 분석 요청 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.VISION_ANALYSIS_FAILED, extractDetail(e).orElse(ErrorCode.VISION_ANALYSIS_FAILED.getMessage()));
        } catch (RestClientException e) {
            log.warn("비전 분석 요청 실패", e);
            throw new CustomException(ErrorCode.VISION_ANALYSIS_FAILED);
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
