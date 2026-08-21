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

    Page<Board> findByCategory(String category, Pageable pageable);

    @Query("""
            SELECT b FROM Board b
            WHERE b.category = :category
              AND (b.author = :author OR (b.author IS NULL AND b.writer = :writer))
            """)
    Page<Board> findByCategoryAndOwner(@Param("category") String category,
                                       @Param("author") User author,
                                       @Param("writer") String writer,
                                       Pageable pageable);

    boolean existsByCategoryAndTitle(String category, String title);

    List<Board> findByWriterOrderByCreatedAtDesc(String writer);

    List<Board> findByAuthorOrderByCreatedAtDesc(User author);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Board b SET b.writer = :newWriter, b.author = null WHERE b.author = :author")
    int anonymizeByAuthor(@Param("author") User author, @Param("newWriter") String newWriter);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Board b SET b.writer = :newWriter WHERE b.writer = :writer AND b.author IS NULL")
    int replaceWriter(@Param("writer") String writer, @Param("newWriter") String newWriter);

}
