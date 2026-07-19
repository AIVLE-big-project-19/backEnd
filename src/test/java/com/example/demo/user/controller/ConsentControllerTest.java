package com.example.demo.user.controller;

import com.example.demo.user.dto.ConsentStatusResponse;
import com.example.demo.user.service.ConsentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConsentController.class)
@AutoConfigureMockMvc(addFilters = false)
class ConsentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConsentService consentService;

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of())
        );
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 내_동의_현황을_조회한다() throws Exception {
        when(consentService.getConsentStatus(1L)).thenReturn(
                ConsentStatusResponse.builder().consents(List.of(
                        ConsentStatusResponse.ConsentItem.builder()
                                .type("TERMS").agreed(true).version("1.0").build(),
                        ConsentStatusResponse.ConsentItem.builder()
                                .type("PRIVACY").agreed(true).version("1.0").build(),
                        ConsentStatusResponse.ConsentItem.builder()
                                .type("MARKETING").agreed(false).version("1.0").build()
                )).build()
        );

        mockMvc.perform(get("/users/me/consents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents[0].type").value("TERMS"))
                .andExpect(jsonPath("$.data.consents[2].type").value("MARKETING"))
                .andExpect(jsonPath("$.data.consents[2].agreed").value(false));
    }

    @Test
    void 마케팅_동의를_변경한다() throws Exception {
        when(consentService.updateMarketingConsent(1L, true)).thenReturn(
                ConsentStatusResponse.ConsentItem.builder()
                        .type("MARKETING").agreed(true).version("1.0").build()
        );

        mockMvc.perform(put("/users/me/consents/marketing")
                        .contentType("application/json")
                        .content("{\"agreed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("MARKETING"))
                .andExpect(jsonPath("$.data.agreed").value(true));
    }

    @Test
    void 마케팅_동의_변경시_agreed가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(put("/users/me/consents/marketing")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
