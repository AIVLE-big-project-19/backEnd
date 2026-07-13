package com.example.demo.comment.entity;

import com.example.demo.board.entity.Board;
import com.example.demo.global.entity.BaseEntity;
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
public class Comment extends BaseEntity {

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


}