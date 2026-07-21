package com.example.demo.board.repository;

import com.example.demo.board.entity.BoardAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BoardAttachmentRepository extends JpaRepository<BoardAttachment, Long> {
}
