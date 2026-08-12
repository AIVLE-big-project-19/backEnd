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

    // 테스트용: 파일을 직접 고르지 않고, S3에 미리 올려둔 CSV(app.idle-land-import 설정)를
    // 그대로 가져와 위 /upload와 동일한 로직으로 처리한다.
    @PostMapping("/upload-from-s3")
    public ApiResponse<IdleLandImportResultDto> uploadFromS3() {
        return ApiResponse.success(SuccessCode.IDLE_LAND_UPLOADED, idleLandCsvImportService.replaceAllFromS3());
    }
}
