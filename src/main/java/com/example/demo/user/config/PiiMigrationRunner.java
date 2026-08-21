package com.example.demo.user.config;

import com.example.demo.user.service.PiiMigrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

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
