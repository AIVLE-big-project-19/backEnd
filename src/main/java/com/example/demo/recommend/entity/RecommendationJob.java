package com.example.demo.recommend.entity;

import com.example.demo.global.entity.BaseEntity;
import com.example.demo.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "recommendation_job")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String externalJobId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false)
    private int limitParam;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(length = 50)
    private String stage;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String funnelJson;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    public void markRunning(String stage) {
        if (this.status == JobStatus.QUEUED) {
            this.startedAt = LocalDateTime.now();
        }
        this.status = JobStatus.RUNNING;
        this.stage = stage;
    }

    public void markDone(String funnelJson) {
        this.status = JobStatus.DONE;
        this.funnelJson = funnelJson;
        this.finishedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = LocalDateTime.now();
    }
}
