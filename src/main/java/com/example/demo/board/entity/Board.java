package com.example.demo.board.entity;

import com.example.demo.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

import com.example.demo.comment.entity.Comment;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "board")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Board extends BaseEntity {

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

    public void update(String title,
                       String content,
                       String category){

        this.title = title;
        this.content = content;
        this.category = category;

    }

    public void increaseViewCount() {
        this.viewCount++;
    }

    @OneToMany(
            mappedBy = "board",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<Comment> comments = new ArrayList<>();

}