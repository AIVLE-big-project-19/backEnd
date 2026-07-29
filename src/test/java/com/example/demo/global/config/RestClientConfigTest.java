package com.example.demo.global.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * visionCsvRestClientBuilder()가 만드는 클라이언트는 cleartext HTTP/2(h2c) 업그레이드를
 * 시도하지 않아야 한다 -- AI 서버(uvicorn/h11)가 Upgrade 헤더가 실린 요청을 프로토콜 단계에서
 * 거부해 모든 호출이 실패하는 문제가 실제로 있었다 (구 timeoutBoundRestClientBuilder에서
 * 검증되던 동일한 회귀 방지 테스트).
 */
class RestClientConfigTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void HTTP2_업그레이드를_시도하지_않는다() throws Exception {
        AtomicBoolean sawUpgradeHeader = new AtomicBoolean(false);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/probe", exchange -> {
            if (exchange.getRequestHeaders().containsKey("Upgrade")) {
                sawUpgradeHeader.set(true);
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();

        RestClientConfig config = new RestClientConfig();
        RestClient.Builder builder = config.visionCsvRestClientBuilder(5_000L);
        RestClient client = builder.baseUrl("http://127.0.0.1:" + port).build();

        client.get().uri("/probe").retrieve().toBodilessEntity();

        assertThat(sawUpgradeHeader).isFalse();
    }
}
