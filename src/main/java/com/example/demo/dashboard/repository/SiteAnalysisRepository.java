package com.example.demo.dashboard.repository;

import com.example.demo.dashboard.entity.SiteAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SiteAnalysisRepository extends JpaRepository<SiteAnalysis, Long> {
    List<SiteAnalysis> findTop10ByUser_IdOrderByCreatedAtDesc(Long userId);
}
