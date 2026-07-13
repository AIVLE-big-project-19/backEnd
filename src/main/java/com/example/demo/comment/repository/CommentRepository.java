package com.example.demo.comment.repository;

import com.example.demo.board.entity.Board;
import com.example.demo.comment.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * 게시글의 댓글 전체 조회
     */
    List<Comment> findByBoard(Board board);

    /**
     * 생성일 기준 오름차순 조회
     */
    List<Comment> findByBoardOrderByCreatedAtAsc(Board board);
}