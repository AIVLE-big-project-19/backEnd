package com.example.demo.board.repository;

import com.example.demo.board.entity.Board;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.example.demo.user.entity.User;

@Repository
public interface BoardRepository extends JpaRepository<Board, Long> {

    List<Board> findByCategory(String category);

    Page<Board> findByCategory(String category, Pageable pageable);

    boolean existsByCategoryAndTitle(String category, String title);

    List<Board> findByTitleContaining(String keyword);

    List<Board> findByWriter(String writer);

    List<Board> findByWriterOrderByCreatedAtDesc(String writer);

    List<Board> findByAuthorOrderByCreatedAtDesc(User author);

    /**
     * 회원탈퇴 시 작성자 익명화: author FK가 설정된(신규) 게시글은 FK로 정확히 매칭해
     * FK를 끊고 표시명을 교체한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Board b SET b.writer = :newWriter, b.author = null WHERE b.author = :author")
    int anonymizeByAuthor(@Param("author") User author, @Param("newWriter") String newWriter);

    /**
     * 회원탈퇴 시 작성자 표시명 교체 (author FK가 없는 레거시 게시글 대상,
     * writer 문자열이 loginId와 일치하는 행만 매칭).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Board b SET b.writer = :newWriter WHERE b.writer = :writer AND b.author IS NULL")
    int replaceWriter(@Param("writer") String writer, @Param("newWriter") String newWriter);

}
