package com.example.demo.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient.Builder visionCsvRestClientBuilder(
            @Value("${ai.server.vision-csv-timeout-ms}") long timeoutMs
    ) {
        // HTTP_1_1을 명시하지 않으면 JDK HttpClient가 매 요청마다 cleartext HTTP/2(h2c) 업그레이드를
        // 먼저 시도한다. AI 서버(uvicorn/h11)는 이 업그레이드 요청을 거부하므로 반드시 HTTP/1.1을 강제해야 한다.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        return RestClient.builder().requestFactory(requestFactory);
    }
}
