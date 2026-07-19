package com.example.demo.user.controller;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.TermsResponse;
import com.example.demo.user.service.TermsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TermsController.class)
@AutoConfigureMockMvc(addFilters = false)
class TermsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TermsService termsService;

    @Test
    void 약관_조회_성공시_본문과_버전을_반환한다() throws Exception {
        when(termsService.getTerms("TERMS")).thenReturn(
                TermsResponse.builder().type("TERMS").version("1.0").content("# 서비스 이용약관").build()
        );

        mockMvc.perform(get("/terms/TERMS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("TERMS"))
                .andExpect(jsonPath("$.data.version").value("1.0"))
                .andExpect(jsonPath("$.data.content").value("# 서비스 이용약관"));
    }

    @Test
    void 존재하지_않는_약관_타입이면_404를_반환한다() throws Exception {
        when(termsService.getTerms("UNKNOWN"))
                .thenThrow(new CustomException(ErrorCode.TERMS_NOT_FOUND));

        mockMvc.perform(get("/terms/UNKNOWN"))
                .andExpect(status().isNotFound());
    }
}
