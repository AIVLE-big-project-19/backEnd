package com.example.demo.idleland.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.idleland.dto.IdleLandSearchResultDto;
import com.example.demo.idleland.service.IdleLandSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/idle-lands")
@RequiredArgsConstructor
public class IdleLandController {

    private final IdleLandSearchService idleLandSearchService;

    @GetMapping("/search")
    public ApiResponse<List<IdleLandSearchResultDto>> search(@RequestParam("q") String q) {
        return ApiResponse.success(SuccessCode.IDLE_LAND_SEARCH_FOUND, idleLandSearchService.search(q));
    }
}
