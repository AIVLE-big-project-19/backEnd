package com.example.demo.recommend.controller;

import com.example.demo.recommend.dto.RecommendationStatusResponse;
import com.example.demo.recommend.dto.RecommendationSubmitResponse;
import com.example.demo.recommend.service.RecommendService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecommendationController.class)
@AutoConfigureMockMvc(addFilters = false)
class RecommendationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecommendService recommendService;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 비로그인_상태로_업로드하면_userId_없이_등록한다() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "sites.xlsx", "application/vnd.ms-excel", "dummy".getBytes());
        when(recommendService.submit(any(), eq(3), isNull()))
                .thenReturn(new RecommendationSubmitResponse(17L, "QUEUED"));

        mockMvc.perform(multipart("/recommendations").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(17))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(recommendService).submit(any(), eq(3), isNull());
    }

    @Test
    void 로그인_상태면_userId를_같이_전달한다() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(5L, null, List.of())
        );

        MockMultipartFile file = new MockMultipartFile("file", "sites.xlsx", "application/vnd.ms-excel", "dummy".getBytes());
        when(recommendService.submit(any(), eq(10), eq(5L)))
                .thenReturn(new RecommendationSubmitResponse(18L, "QUEUED"));

        mockMvc.perform(multipart("/recommendations?limit=10").file(file))
                .andExpect(status().isOk());

        verify(recommendService).submit(any(), eq(10), eq(5L));
    }

    @Test
    void 완료된_job을_조회하면_추천목록을_반환한다() throws Exception {
        when(recommendService.getStatus(eq(17L), isNull())).thenReturn(new RecommendationStatusResponse(
                17L, "DONE", null, Map.of("node0_parsed", 230), List.of(), null
        ));

        mockMvc.perform(get("/recommendations/17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.funnel.node0_parsed").value(230));
    }

    @Test
    void 로그인한_사용자가_조회하면_userId를_같이_전달한다() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(5L, null, List.of())
        );

        when(recommendService.getStatus(eq(17L), eq(5L))).thenReturn(new RecommendationStatusResponse(
                17L, "DONE", null, Map.of("node0_parsed", 230), List.of(), null
        ));

        mockMvc.perform(get("/recommendations/17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"));

        verify(recommendService).getStatus(eq(17L), eq(5L));
    }
}
