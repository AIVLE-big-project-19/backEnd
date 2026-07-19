package com.example.demo.user.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class ConsentStatusResponse {

    private List<ConsentItem> consents;

    @Getter
    @Builder
    public static class ConsentItem {

        private String type;

        private Boolean agreed;

        private String version;

        private LocalDateTime agreedAt;
    }
}
