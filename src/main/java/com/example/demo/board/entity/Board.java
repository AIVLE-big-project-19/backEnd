package com.example.demo.board.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "board")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Board {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long boardId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    // 작성자_이후 수정 필요
    @Column(nullable = false, length = 30)
    private String writer;

    // 게시판 종류 (공지사항, 자유게시판 등)
    @Column(nullable = false, length = 30)
    private String category;

    @Builder.Default
    @Column(nullable = false)
    private Integer viewCount = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 게시글 생성 시 자동 실행
     */
    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();

        if (this.viewCount == null) {
            this.viewCount = 0;
        }
    }

    /**
     * 게시글 수정 시 자동 실행
     */
    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 조회수 증가
     */
    public void increaseViewCount() {
        this.viewCount++;
    }
}