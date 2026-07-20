package com.example.demo.board.repository;

import com.example.demo.board.entity.Board;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BoardRepository extends JpaRepository<Board, Long> {

    List<Board> findByCategory(String category);

    List<Board> findByTitleContaining(String keyword);

    List<Board> findByWriter(String writer);

    List<Board> findByWriterOrderByCreatedAtDesc(String writer);

    /**
     * 회원탈퇴 시 작성자 표시명 교체 (Board는 User FK가 없어 loginId 문자열 기준).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Board b SET b.writer = :newWriter WHERE b.writer = :writer")
    int replaceWriter(@Param("writer") String writer, @Param("newWriter") String newWriter);

}
