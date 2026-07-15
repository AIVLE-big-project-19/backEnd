package com.example.demo.chat.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatActionDto {

    private String type;
    private String path;

}
