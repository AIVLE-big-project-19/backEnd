package com.example.demo.dashboard.repository;

import com.example.demo.dashboard.entity.SiteAnalysis;
import com.example.demo.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SiteAnalysisRepository extends JpaRepository<SiteAnalysis, Long> {
    void deleteByUser(User user);
}
