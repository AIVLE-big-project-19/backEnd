package com.example.demo.board.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
public class BoardStorageConfig {

    @Bean(destroyMethod = "close")
    public S3Client boardS3Client(@Value("${app.board-storage.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }
}
