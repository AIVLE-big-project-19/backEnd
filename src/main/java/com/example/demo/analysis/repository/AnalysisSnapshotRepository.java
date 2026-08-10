package com.example.demo.analysis.repository;

import com.example.demo.analysis.entity.AnalysisSnapshot;
import com.example.demo.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisSnapshotRepository extends JpaRepository<AnalysisSnapshot, Long> {
    java.util.List<AnalysisSnapshot> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    java.util.List<AnalysisSnapshot> findAllByIdInAndUserId(java.util.List<Long> ids, Long userId);

    void deleteAllByUserId(Long userId);

    java.util.List<AnalysisSnapshot> findAllByOrderByCreatedAtDesc();

    void deleteByUser(User user);
}
