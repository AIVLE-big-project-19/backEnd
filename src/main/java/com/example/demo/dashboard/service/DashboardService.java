package com.example.demo.dashboard.service;

import com.example.demo.dashboard.dto.SiteAnalysisRequest;
import com.example.demo.dashboard.dto.SiteAnalysisResponse;

import java.util.List;

public interface DashboardService {
    SiteAnalysisResponse analyze(Long userId, SiteAnalysisRequest request);
    List<SiteAnalysisResponse> history(Long userId);
}
