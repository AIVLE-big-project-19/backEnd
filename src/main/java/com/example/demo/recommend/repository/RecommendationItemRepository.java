package com.example.demo.recommend.repository;

import com.example.demo.recommend.entity.RecommendationItem;
import com.example.demo.recommend.entity.RecommendationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecommendationItemRepository extends JpaRepository<RecommendationItem, Long> {
    List<RecommendationItem> findByJobOrderById(RecommendationJob job);
}
