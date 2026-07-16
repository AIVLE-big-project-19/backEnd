package com.example.demo.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoogleLoginRequest {

    @NotBlank(message = "인가 코드는 필수입니다.")
    private String code;

    @NotBlank(message = "redirectUri는 필수입니다.")
    private String redirectUri;
}
