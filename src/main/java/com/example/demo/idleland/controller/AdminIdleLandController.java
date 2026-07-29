package com.example.demo.idleland.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.idleland.dto.IdleLandImportResultDto;
import com.example.demo.idleland.service.IdleLandCsvImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// /admin/** 는 SecurityConfig에서 hasRole("ADMIN")으로 자동 보호됨
@RestController
@RequestMapping("/admin/idle-lands")
@RequiredArgsConstructor
public class AdminIdleLandController {

    private final IdleLandCsvImportService idleLandCsvImportService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<IdleLandImportResultDto> upload(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(SuccessCode.IDLE_LAND_UPLOADED, idleLandCsvImportService.replaceAll(file));
    }
}
