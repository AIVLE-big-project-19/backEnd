package com.example.demo.visionanalysis.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

class VisionAnalysisClientTest {

    private MockRestServiceServer mockServer;
    private VisionAnalysisClient visionAnalysisClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        visionAnalysisClient = new VisionAnalysisClient(builder, "http://ai-server.test", "/vision/csv", JsonMapper.builder().build());
    }

    @Test
    void 분석에_성공하면_CSV_바이트를_그대로_반환한다() {
        byte[] csvBytes = "id,score\n1,0.9\n".getBytes();
        mockServer.expect(requestTo("http://ai-server.test/vision/csv?limit=3"))
                .andExpect(method(POST))
                .andRespond(withSuccess(csvBytes, MediaType.parseMediaType("text/csv")));

        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());

        byte[] result = visionAnalysisClient.fetchCsv(file, 3);

        assertThat(result).isEqualTo(csvBytes);
        mockServer.verify();
    }

    @Test
    void 파일_파트에_원본_파일명이_그대로_실린다() {
        mockServer.expect(requestTo("http://ai-server.test/vision/csv?limit=3"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("filename=\"sites.csv\"")))
                .andRespond(withSuccess("id\n1\n".getBytes(), MediaType.parseMediaType("text/csv")));

        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());

        visionAnalysisClient.fetchCsv(file, 3);

        mockServer.verify();
    }

    @Test
    void 분석이_400이면_detail_메시지를_담은_예외를_던진다() {
        mockServer.expect(requestTo("http://ai-server.test/vision/csv?limit=3"))
                .andRespond(withBadRequest()
                        .body("{\"detail\":\"CSV 파일만 업로드할 수 있습니다.\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        MockMultipartFile file = new MockMultipartFile("file", "sites.txt", "text/plain", "dummy".getBytes());

        assertThatThrownBy(() -> visionAnalysisClient.fetchCsv(file, 3))
                .isInstanceOf(CustomException.class)
                .hasMessage("CSV 파일만 업로드할 수 있습니다.")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.VISION_ANALYSIS_FAILED);
    }

    @Test
    void 분석이_400이고_body에_detail이_없으면_기본_메시지를_담은_예외를_던진다() {
        mockServer.expect(requestTo("http://ai-server.test/vision/csv?limit=3"))
                .andRespond(withBadRequest()
                        .body("{\"other_field\":\"value\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        MockMultipartFile file = new MockMultipartFile("file", "sites.txt", "text/plain", "dummy".getBytes());

        assertThatThrownBy(() -> visionAnalysisClient.fetchCsv(file, 3))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.VISION_ANALYSIS_FAILED.getMessage())
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.VISION_ANALYSIS_FAILED);
    }

    @Test
    void 서버_오류가_나면_예외를_던진다() {
        mockServer.expect(requestTo("http://ai-server.test/vision/csv?limit=3"))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR));

        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());

        assertThatThrownBy(() -> visionAnalysisClient.fetchCsv(file, 3))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.VISION_ANALYSIS_FAILED);
    }
}
