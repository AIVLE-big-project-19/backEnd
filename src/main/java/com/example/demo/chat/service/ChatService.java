package com.example.demo.chat.service;

import com.example.demo.chat.dto.ChatRequest;
import com.example.demo.chat.dto.ChatResponse;

public interface ChatService {

    ChatResponse sendMessage(Long userId, ChatRequest request);

}
