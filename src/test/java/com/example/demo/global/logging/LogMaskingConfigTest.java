package com.example.demo.global.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * logback-spring.xml의 실제 패턴으로 로그를 찍어보고 password/token 값이
 * 출력물에 남지 않는지 확인한다.
 */
class LogMaskingConfigTest {

    @Test
    void 비밀번호와_토큰은_로그에_원문으로_남지_않는다() throws Exception {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            URL configUrl = getClass().getClassLoader().getResource("logback-spring.xml");
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();
            configurator.doConfigure(configUrl);

            Logger logger = context.getLogger("test.masking");
            logger.info("login attempt password=hunter2 accessToken=eyJhead.payload.sig");
            logger.info("Authorization: Bearer eyJhead.payload.sig2");
        } finally {
            System.setOut(originalOut);
        }

        String logged = buffer.toString(StandardCharsets.UTF_8);

        assertThat(logged).doesNotContain("hunter2");
        assertThat(logged).doesNotContain("eyJhead.payload.sig");
        assertThat(logged).doesNotContain("eyJhead.payload.sig2");
        assertThat(logged).contains("password=***");
        assertThat(logged).contains("Bearer ***");
    }
}
