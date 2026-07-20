package com.example.demo.board.repository;

import com.example.demo.board.entity.Board;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.example.demo.user.entity.User;

@Repository
public interface BoardRepository extends JpaRepository<Board, Long> {

    List<Board> findByCategory(String category);

    Page<Board> findByCategory(String category, Pageable pageable);

    List<Board> findByTitleContaining(String keyword);

    List<Board> findByWriter(String writer);

    List<Board> findByWriterOrderByCreatedAtDesc(String writer);

    List<Board> findByAuthorOrderByCreatedAtDesc(User author);

}
