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
    public RestClient.Builder timeoutBoundRestClientBuilder(
            @Value("${ai.server.request-timeout-ms}") long requestTimeoutMs
    ) {
        // HTTP_1_1을 명시하지 않으면 JDK HttpClient가 매 요청마다 cleartext HTTP/2(h2c) 업그레이드를
        // 먼저 시도한다(Connection: Upgrade, Upgrade: h2c). AI 서버(uvicorn/h11)는 이 업그레이드
        // 요청을 정상적으로 무시하지 못하고 요청 자체를 거부하므로("Invalid HTTP request received"),
        // 반드시 HTTP/1.1을 강제해야 한다.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(requestTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(requestTimeoutMs));

        return RestClient.builder().requestFactory(requestFactory);
    }
}
