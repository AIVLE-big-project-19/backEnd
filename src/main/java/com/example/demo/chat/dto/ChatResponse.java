package com.example.demo.chat.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatResponse {

    private String reply;
    private ChatActionDto action;

}
