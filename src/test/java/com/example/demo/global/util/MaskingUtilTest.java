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
}
