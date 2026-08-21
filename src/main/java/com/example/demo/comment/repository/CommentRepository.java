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

    List<Comment> findByBoardOrderByCreatedAtAsc(Board board);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Comment c SET c.writer = :writer, c.author = null WHERE c.author = :author")
    int anonymizeByAuthor(@Param("author") User author, @Param("writer") String writer);
}
