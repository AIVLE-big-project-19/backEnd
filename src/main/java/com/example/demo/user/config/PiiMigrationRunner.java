package com.example.demo.user.config;

import com.example.demo.user.service.PiiMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 기동 시 emailHash가 비어있는(=암호화 적용 전에 만들어진) 기존 행을 찾아
 * 백필한다. 다른 ApplicationRunner(데모 데이터 초기화 등)보다 먼저 돌도록
 * 순서를 낮게 잡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(0)
public class PiiMigrationRunner implements ApplicationRunner {

    private final PiiMigrationService piiMigrationService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            piiMigrationService.migratePendingUsers();
        } catch (Exception e) {
            log.error("PII 백필 실패 — 레거시 평문 행은 다음 기동에 재시도됩니다.", e);
        }
    }
}
