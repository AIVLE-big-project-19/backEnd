package com.example.demo.board.repository;

import com.example.demo.board.entity.Board;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BoardRepository extends JpaRepository<Board, Long> {

    List<Board> findByCategory(String category);

    List<Board> findByTitleContaining(String keyword);

    List<Board> findByWriter(String writer);

}