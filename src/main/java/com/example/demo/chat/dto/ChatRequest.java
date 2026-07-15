package com.example.demo.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatRequest {

    @NotBlank(message = "메시지는 필수입니다.")
    private String message;

}
