package com.example.demo.test.repository;

import com.example.demo.test.entity.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnalysisRepository extends JpaRepository<AnalysisResult, Long> {

    // 1. 특정 사용자의 모든 분석 이력 조회 (기본 제공 기능 외 추가 메서드 예시)
    List<AnalysisResult> findByUserId(Long userId);

    // 2. 특정 상태(예: IN_PROGRESS)인 분석 작업 목록 조회
    List<AnalysisResult> findByStatus(AnalysisResult.AnalysisStatus status);
}