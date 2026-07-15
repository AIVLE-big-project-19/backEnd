package com.example.demo.global.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAiConfig {

    @Value("${OPENAI_API_KEY:}")
    private String openAiApiKey;

    @Bean
    public OpenAIClient openAIClient() {
        return OpenAIOkHttpClient.builder()
                .apiKey(openAiApiKey)
                .build();
    }

}
