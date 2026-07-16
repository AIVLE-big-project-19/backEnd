package com.example.demo.user.oauth;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class GoogleOAuthClient {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URI = "https://www.googleapis.com/oauth2/v3/userinfo";

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    public GoogleOAuthClient(
            RestClient.Builder restClientBuilder,
            @Value("${google.client-id}") String clientId,
            @Value("${google.client-secret}") String clientSecret
    ) {
        this.restClient = restClientBuilder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public GoogleUserInfo fetchUserInfo(String code, String redirectUri) {
        String accessToken = exchangeCodeForAccessToken(code, redirectUri);
        return fetchUserInfo(accessToken);
    }

    private String exchangeCodeForAccessToken(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        Map<String, Object> response = post(TOKEN_URI, form);

        if (response == null || response.get("access_token") == null) {
            throw new CustomException(ErrorCode.GOOGLE_AUTH_FAILED);
        }

        return response.get("access_token").toString();
    }

    private GoogleUserInfo fetchUserInfo(String accessToken) {
        Map<String, Object> response = getUserInfo(accessToken);

        if (response == null || response.get("sub") == null || response.get("email") == null) {
            throw new CustomException(ErrorCode.GOOGLE_AUTH_FAILED);
        }

        Object name = response.getOrDefault("name", "");

        return GoogleUserInfo.builder()
                .providerId(response.get("sub").toString())
                .email(response.get("email").toString())
                .name(name.toString())
                .build();
    }

    private Map<String, Object> post(String uri, MultiValueMap<String, String> form) {
        try {
            return restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientException e) {
            throw new CustomException(ErrorCode.GOOGLE_AUTH_FAILED);
        }
    }

    private Map<String, Object> getUserInfo(String accessToken) {
        try {
            return restClient.get()
                    .uri(USERINFO_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientException e) {
            throw new CustomException(ErrorCode.GOOGLE_AUTH_FAILED);
        }
    }
}
