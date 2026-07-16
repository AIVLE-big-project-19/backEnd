package com.example.demo.user.oauth;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GoogleUserInfo {

    private String providerId;

    private String email;

    private String name;
}
