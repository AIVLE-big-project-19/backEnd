package com.example.demo.analysis.repository;

import com.example.demo.analysis.entity.AnalysisSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisSnapshotRepository extends JpaRepository<AnalysisSnapshot, Long> {
    java.util.List<AnalysisSnapshot> findAllByUserIdOrderByCreatedAtDesc(Long userId);
}
