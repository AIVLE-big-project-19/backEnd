package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.TermsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TermsServiceTest {

    private TermsService termsService;

    @BeforeEach
    void setUp() {
        termsService = new TermsService("1.0");
    }

    @Test
    void 이용약관_본문을_조회할_수_있다() {
        TermsResponse response = termsService.getTerms("TERMS");

        assertThat(response.getType()).isEqualTo("TERMS");
        assertThat(response.getVersion()).isEqualTo("1.0");
        assertThat(response.getContent()).contains("서비스 이용약관");
    }

    @Test
    void 개인정보처리방침_본문을_소문자_타입으로도_조회할_수_있다() {
        TermsResponse response = termsService.getTerms("privacy");

        assertThat(response.getType()).isEqualTo("PRIVACY");
        assertThat(response.getContent()).contains("개인정보");
    }

    @Test
    void 존재하지_않는_타입이면_예외가_발생한다() {
        assertThatThrownBy(() -> termsService.getTerms("UNKNOWN"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TERMS_NOT_FOUND);
    }

    @Test
    void MARKETING_타입은_본문_문서가_없으므로_예외가_발생한다() {
        assertThatThrownBy(() -> termsService.getTerms("MARKETING"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TERMS_NOT_FOUND);
    }
}
