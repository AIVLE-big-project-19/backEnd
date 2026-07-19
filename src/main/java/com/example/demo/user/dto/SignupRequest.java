package com.example.demo.user.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SignupRequest {

    @NotBlank(message = "아이디는 필수입니다.")
    @Size(min = 4, max = 20, message = "아이디는 4~20자로 입력하세요.")
    private String loginId;

    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank(message = "비밀번호는 필수입니다.")
    @Pattern(
            regexp = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[^A-Za-z0-9]).{8,16}$",
            message = "비밀번호는 8~16자이며 영문, 숫자, 특수문자를 모두 포함해야 합니다."
    )
    private String password;

    @NotBlank(message = "이름은 필수입니다.")
    private String name;

    @AssertTrue(message = "필수 약관에 동의해야 합니다.")
    private boolean termsAgreed;

    @AssertTrue(message = "필수 약관에 동의해야 합니다.")
    private boolean privacyAgreed;

    private boolean marketingAgreed;
}
