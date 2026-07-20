package com.example.demo.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WithdrawalRequest {

    // LOCAL 계정은 필수, 구글 계정은 미전송 허용 — 검증은 WithdrawalService에서 수행
    private String password;
}
