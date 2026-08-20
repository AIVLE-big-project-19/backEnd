package com.example.demo.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WithdrawalRequest {

    // 참고: LOCAL 계정만 비밀번호가 필수이며 계정 제공자별 검증은 WithdrawalService가 담당한다.
    private String password;
}
