package com.example.demo.visionanalysis.controller;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.visionanalysis.client.VisionAnalysisClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.util.concurrent.Semaphore;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VisionAnalysisController.class)
@AutoConfigureMockMvc(addFilters = false)
class VisionAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VisionAnalysisController visionAnalysisController;

    @MockitoBean
    private VisionAnalysisClient visionAnalysisClient;

    private Semaphore concurrencyLimiter;

    @AfterEach
    void releasePermits() throws Exception {
        if (concurrencyLimiter != null) {
            // 테스트에서 강제로 permit을 모두 소진시켰다면, 다음 테스트에 영향을 주지 않도록 원복한다.
            while (concurrencyLimiter.availablePermits() < 5) {
                concurrencyLimiter.release();
            }
        }
    }

    @Test
    void 분석에_성공하면_CSV_바이트와_헤더를_그대로_반환한다() throws Exception {
        byte[] csvBytes = "id,score\n1,0.9\n".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());
        when(visionAnalysisClient.fetchCsv(any(), eq(3))).thenReturn(csvBytes);

        mockMvc.perform(multipart("/vision-analysis/csv").file(file))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"vision_analysis_result.csv\""))
                .andExpect(content().bytes(csvBytes));

        verify(visionAnalysisClient).fetchCsv(any(), eq(3));
    }

    @Test
    void limit_쿼리파라미터를_클라이언트에_그대로_전달한다() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());
        when(visionAnalysisClient.fetchCsv(any(), eq(10))).thenReturn("id\n1\n".getBytes());

        mockMvc.perform(multipart("/vision-analysis/csv?limit=10").file(file))
                .andExpect(status().isOk());

        verify(visionAnalysisClient).fetchCsv(any(), eq(10));
    }

    @Test
    void 클라이언트가_예외를_던지면_표준_에러_응답을_반환한다() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "sites.txt", "text/plain", "dummy".getBytes());
        when(visionAnalysisClient.fetchCsv(any(), eq(3)))
                .thenThrow(new CustomException(ErrorCode.VISION_ANALYSIS_FAILED, "CSV 파일만 업로드할 수 있습니다."));

        mockMvc.perform(multipart("/vision-analysis/csv").file(file))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("CSV 파일만 업로드할 수 있습니다."));
    }

    @Test
    void limit이_범위를_벗어나면_400을_반환한다() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());

        mockMvc.perform(multipart("/vision-analysis/csv?limit=0").file(file))
                .andExpect(status().isBadRequest());

        mockMvc.perform(multipart("/vision-analysis/csv?limit=21").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 동시_처리_한도를_초과하면_503을_반환한다() throws Exception {
        // 실제 스레드로 동시성을 재현하면 타이밍에 의존하는 flaky 테스트가 되므로,
        // 컨트롤러가 내부적으로 사용하는 Semaphore의 permit을 리플렉션으로 직접 모두 소진시켜
        // "한도 초과" 상태를 결정적으로 재현한다.
        concurrencyLimiter = getConcurrencyLimiter();
        concurrencyLimiter.acquire(5);

        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());

        mockMvc.perform(multipart("/vision-analysis/csv").file(file))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));
    }

    private Semaphore getConcurrencyLimiter() throws Exception {
        // @Validated로 인해 컨트롤러 빈이 메서드 검증용 CGLIB 프록시로 감싸져 있으므로,
        // 실제 필드 값을 가진 타깃 인스턴스를 꺼내야 리플렉션으로 올바른 Semaphore를 얻을 수 있다.
        Object target = AopTestUtils.getTargetObject(visionAnalysisController);
        Field field = VisionAnalysisController.class.getDeclaredField("concurrencyLimiter");
        field.setAccessible(true);
        return (Semaphore) field.get(target);
    }
}
