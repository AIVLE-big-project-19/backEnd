package com.example.demo.recommend.controller;

import com.example.demo.recommend.dto.RecommendationHistoryResponse;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    @Test
    void 비로그인_상태로_이력을_조회하면_서비스_호출_없이_실패_응답을_준다() throws Exception {
        mockMvc.perform(get("/recommendations/me"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("로그인 후 추천 이력을 조회할 수 있습니다."));

        verify(recommendService, never()).getHistory(any());
    }

    @Test
    void 로그인_상태로_이력을_조회하면_userId로_조회한다() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(5L, null, List.of())
        );

        when(recommendService.getHistory(5L)).thenReturn(List.of(
                new RecommendationHistoryResponse(17L, "대전광역시_유휴공간.xlsx", "DONE", null, null,
                        java.time.LocalDateTime.of(2026, 7, 28, 14, 16))
        ));

        mockMvc.perform(get("/recommendations/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(17))
                .andExpect(jsonPath("$.data[0].status").value("DONE"));

        verify(recommendService).getHistory(5L);
    }

    // DELETE는 SecurityConfig에서 인증을 강제하므로(비로그인 요청은 컨트롤러에 도달하지 않음),
    // 여기서는 인증된 호출이 서비스에 id/userId를 올바르게 전달하는지만 검증한다.
    @Test
    void 로그인_상태로_삭제하면_userId를_같이_전달한다() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(5L, null, List.of())
        );

        mockMvc.perform(delete("/recommendations/17"))
                .andExpect(status().isOk());

        verify(recommendService).delete(17L, 5L);
    }
}
