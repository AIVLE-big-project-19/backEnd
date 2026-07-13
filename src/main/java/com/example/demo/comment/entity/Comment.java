package com.example.demo.comment.entity;

import com.example.demo.board.entity.Board;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "comment")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long commentId;

    /**
     * 게시글
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    /**
     * 작성자
     */
    @Column(nullable = false, length = 30)
    private String writer;

    /**
     * 댓글 내용
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * 생성일
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * 수정일
     */
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}