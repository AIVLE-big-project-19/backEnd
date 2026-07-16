package com.example.demo.chat.controller;

import com.example.demo.chat.dto.ChatRequest;
import com.example.demo.chat.dto.ChatResponse;
import com.example.demo.chat.service.ChatService;
import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public ApiResponse<ChatResponse> sendMessage(@Valid @RequestBody ChatRequest request) {

        return ApiResponse.success(
                SuccessCode.CHAT_REPLIED,
                chatService.sendMessage(currentUserId(), request)
        );

    }

    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ChatResponse> analyzeExcel(@RequestParam("file") MultipartFile file) {

        return ApiResponse.success(
                SuccessCode.CHAT_EXCEL_ANALYZED,
                chatService.analyzeExcel(currentUserId(), file)
        );

    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication != null ? authentication.getPrincipal() : null;

        return principal instanceof Long ? (Long) principal : null;
    }

}
