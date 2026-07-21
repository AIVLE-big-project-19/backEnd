package com.example.demo.board.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "board_attachment")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BoardAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long attachmentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false)
    private Board board;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false, unique = true, length = 100)
    private String storedFilename;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private long fileSize;
}
