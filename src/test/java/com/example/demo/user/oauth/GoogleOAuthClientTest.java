package com.example.demo.user.oauth;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleOAuthClientTest {

    private MockRestServiceServer mockServer;
    private GoogleOAuthClient googleOAuthClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        googleOAuthClient = new GoogleOAuthClient(builder, "test-client-id", "test-client-secret");
    }

    @Test
    void 인가코드를_사용자정보로_교환한다() {
        mockServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"google-access-token\",\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON
                ));

        mockServer.expect(requestTo("https://www.googleapis.com/oauth2/v3/userinfo"))
                .andExpect(header("Authorization", "Bearer google-access-token"))
                .andRespond(withSuccess(
                        "{\"sub\":\"1234567890\",\"email\":\"tester@gmail.com\",\"name\":\"테스터\"}",
                        MediaType.APPLICATION_JSON
                ));

        GoogleUserInfo result = googleOAuthClient.fetchUserInfo("auth-code", "http://localhost:5173/oauth/google/callback");

        assertThat(result.getProviderId()).isEqualTo("1234567890");
        assertThat(result.getEmail()).isEqualTo("tester@gmail.com");
        assertThat(result.getName()).isEqualTo("테스터");

        mockServer.verify();
    }

    @Test
    void 토큰_교환에_실패하면_예외가_발생한다() {
        mockServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> googleOAuthClient.fetchUserInfo("bad-code", "http://localhost:5173/oauth/google/callback"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GOOGLE_AUTH_FAILED);
    }

    @Test
    void 사용자정보_조회에_실패하면_예외가_발생한다() {
        mockServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"google-access-token\",\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON
                ));

        mockServer.expect(requestTo("https://www.googleapis.com/oauth2/v3/userinfo"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> googleOAuthClient.fetchUserInfo("auth-code", "http://localhost:5173/oauth/google/callback"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GOOGLE_AUTH_FAILED);
    }
}
