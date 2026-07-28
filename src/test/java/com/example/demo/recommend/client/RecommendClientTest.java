package com.example.demo.recommend.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.recommend.client.dto.JobStatusResult;
import com.example.demo.recommend.client.dto.JobSubmitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RecommendClientTest {

    private MockRestServiceServer mockServer;
    private RecommendClient recommendClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        recommendClient = new RecommendClient(builder, "http://ai-server.test", "/recommend/jobs", JsonMapper.builder().build());
    }

    @Test
    void job_등록에_성공하면_jobId를_반환한다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs?limit=3"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"job_id\":\"job-123\",\"status\":\"queued\"}", MediaType.APPLICATION_JSON));

        MockMultipartFile file = new MockMultipartFile("file", "sites.xlsx", "application/vnd.ms-excel", "dummy".getBytes());

        JobSubmitResult result = recommendClient.submitJob(file, 3);

        assertThat(result.getJobId()).isEqualTo("job-123");
        mockServer.verify();
    }

    @Test
    void 파일_파트에_원본_파일명이_그대로_실린다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs?limit=3"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("filename=\"sites.xlsx\"")))
                .andRespond(withSuccess("{\"job_id\":\"job-999\"}", MediaType.APPLICATION_JSON));

        MockMultipartFile file = new MockMultipartFile("file", "sites.xlsx", "application/vnd.ms-excel", "dummy".getBytes());

        recommendClient.submitJob(file, 3);

        mockServer.verify();
    }

    @Test
    void job_등록이_400이면_detail_메시지를_담은_예외를_던진다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs?limit=3"))
                .andRespond(withBadRequest()
                        .body("{\"detail\":\"엑셀 파일(.xlsx, .xls)만 업로드할 수 있습니다.\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        MockMultipartFile file = new MockMultipartFile("file", "sites.txt", "text/plain", "dummy".getBytes());

        assertThatThrownBy(() -> recommendClient.submitJob(file, 3))
                .isInstanceOf(CustomException.class)
                .hasMessage("엑셀 파일(.xlsx, .xls)만 업로드할 수 있습니다.")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_RECOMMEND_FAILED);
    }

    @Test
    void job_등록이_400이고_body에_detail이_없으면_기본_메시지를_담은_예외를_던진다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs?limit=3"))
                .andRespond(withBadRequest()
                        .body("{\"other_field\":\"value\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        MockMultipartFile file = new MockMultipartFile("file", "sites.txt", "text/plain", "dummy".getBytes());

        assertThatThrownBy(() -> recommendClient.submitJob(file, 3))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.AI_RECOMMEND_FAILED.getMessage())
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_RECOMMEND_FAILED);
    }

    @Test
    void 폴링_응답이_done이면_결과를_파싱한다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs/job-123"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"status\":\"done\",\"stage\":null,\"result\":{\"funnel\":{\"node0_parsed\":10},\"recommendations\":[]}}",
                        MediaType.APPLICATION_JSON
                ));

        JobStatusResult result = recommendClient.pollJob("job-123");

        assertThat(result.getStatus()).isEqualTo("done");
        assertThat(result.getResult().getFunnel()).containsEntry("node0_parsed", 10);
    }

    @Test
    void 폴링_응답이_404면_null을_반환한다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs/lost-job"))
                .andRespond(withStatus(NOT_FOUND));

        JobStatusResult result = recommendClient.pollJob("lost-job");

        assertThat(result).isNull();
    }

    @Test
    void 폴링_중_서버오류가_나면_예외를_던진다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs/job-500"))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> recommendClient.pollJob("job-500"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_RECOMMEND_FAILED);
    }
}
