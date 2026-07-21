package com.example.demo.comment.repository;

import com.example.demo.board.entity.Board;
import com.example.demo.comment.entity.Comment;
import com.example.demo.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 회원탈퇴 시 작성자 익명화: author FK를 끊고 표시명을 교체한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Comment c SET c.writer = :writer, c.author = null WHERE c.author = :author")
    int anonymizeByAuthor(@Param("author") User author, @Param("writer") String writer);
}
