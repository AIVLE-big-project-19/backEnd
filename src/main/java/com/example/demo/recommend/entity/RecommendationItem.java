package com.example.demo.recommend.entity;

import com.example.demo.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "recommendation_item")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private RecommendationJob job;

    @Column(length = 20)
    private String targetType;

    @Column(length = 50)
    private String siteId;

    @Column(length = 255)
    private String address;

    @Column(length = 10)
    private String grade;

    private Integer totalScore;

    @Column(length = 20)
    private String priorityRank;

    @Column(length = 20)
    private String status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
}
