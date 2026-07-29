package com.example.demo.fastApi.repository;

import com.example.demo.fastApi.entity.SolarAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SolarAnalysisRepository extends JpaRepository<SolarAnalysis, Long> {
}