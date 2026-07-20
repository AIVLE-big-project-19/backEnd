package com.example.demo.chat.service;

import com.example.demo.chat.dto.ChatRequest;
import com.example.demo.chat.dto.ChatResponse;
import org.springframework.web.multipart.MultipartFile;

public interface ChatService {

    ChatResponse sendMessage(Long userId, ChatRequest request);

    ChatResponse analyzeExcel(Long userId, MultipartFile file);

}
