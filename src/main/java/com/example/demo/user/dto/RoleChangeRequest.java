package com.example.demo.user.dto;

import com.example.demo.user.entity.Role;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RoleChangeRequest {
    @NotNull(message = "권한을 선택해주세요.")
    private Role role;
}
