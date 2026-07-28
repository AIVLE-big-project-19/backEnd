package com.example.demo.recommend.repository;

import com.example.demo.recommend.entity.RecommendationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RecommendationJobRepository extends JpaRepository<RecommendationJob, Long> {
}
