package com.example.demo.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TermsResponse {

    private String type;

    private String version;

    private String content;
}
