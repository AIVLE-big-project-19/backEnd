package com.example.demo.recommend.repository;

import com.example.demo.recommend.entity.RecommendationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecommendationJobRepository extends JpaRepository<RecommendationJob, Long> {

    List<RecommendationJob> findTop10ByUser_IdOrderByCreatedAtDesc(Long userId);

    List<RecommendationJob> findByUserIsNullAndCreatedAtBefore(LocalDateTime cutoff);
}
