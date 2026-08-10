package com.example.demo.global.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MaskingUtilTest {

    @Test
    void 이름이_null이면_그대로_반환한다() {
        assertThat(MaskingUtil.maskName(null)).isNull();
    }

    @Test
    void 이름이_빈문자열이면_그대로_반환한다() {
        assertThat(MaskingUtil.maskName("")).isEqualTo("");
    }

    @Test
    void 이름이_한글자면_그대로_반환한다() {
        assertThat(MaskingUtil.maskName("이")).isEqualTo("이");
    }

    @Test
    void 이름이_두글자면_첫글자만_남기고_가린다() {
        assertThat(MaskingUtil.maskName("이도")).isEqualTo("이*");
    }

    @Test
    void 이름이_세글자면_첫글자만_남기고_나머지를_가린다() {
        assertThat(MaskingUtil.maskName("한승연")).isEqualTo("한**");
    }

    @Test
    void 이름에_공백이_포함되어도_길이만큼_가린다() {
        assertThat(MaskingUtil.maskName("z z")).isEqualTo("z**");
    }

    @Test
    void 이메일이_null이면_그대로_반환한다() {
        assertThat(MaskingUtil.maskEmail(null)).isNull();
    }

    @Test
    void 골뱅이가_없으면_그대로_반환한다() {
        assertThat(MaskingUtil.maskEmail("not-an-email")).isEqualTo("not-an-email");
    }

    @Test
    void 로컬파트가_한글자면_그대로_노출하고_나머지는_없다() {
        assertThat(MaskingUtil.maskEmail("a@gmail.com")).isEqualTo("a@gmail.com");
    }

    @Test
    void 로컬파트가_두글자면_앞한글자만_남기고_가린다() {
        assertThat(MaskingUtil.maskEmail("ab@gmail.com")).isEqualTo("a*@gmail.com");
    }

    @Test
    void 로컬파트가_세글자면_전부_노출된다() {
        assertThat(MaskingUtil.maskEmail("abc@gmail.com")).isEqualTo("abc@gmail.com");
    }

    @Test
    void 로컬파트가_세글자보다_길면_앞세글자만_남기고_가린다() {
        assertThat(MaskingUtil.maskEmail("s2ungyeon.h@gmail.com")).isEqualTo("s2u********@gmail.com");
    }

    @Test
    void 도메인은_항상_그대로_유지된다() {
        assertThat(MaskingUtil.maskEmail("htmddus49@gmail.com")).isEqualTo("htm******@gmail.com");
    }
}
