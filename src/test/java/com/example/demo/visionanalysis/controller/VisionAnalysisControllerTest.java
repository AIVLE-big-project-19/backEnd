package com.example.demo.visionanalysis.controller;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.visionanalysis.client.VisionAnalysisClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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

    @MockitoBean
    private VisionAnalysisClient visionAnalysisClient;

    @Test
    void 분석에_성공하면_CSV_바이트와_헤더를_그대로_반환한다() throws Exception {
        byte[] csvBytes = "id,score\n1,0.9\n".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());
        when(visionAnalysisClient.fetchCsv(any(), eq(3))).thenReturn(csvBytes);

        mockMvc.perform(multipart("/vision-analysis/csv").file(file))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv"))
                .andExpect(header().string("Content-Disposition", "form-data; name=\"attachment\"; filename=\"vision_analysis_result.csv\""))
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
}
