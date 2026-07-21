package com.example.demo.board.service;

import com.example.demo.board.dto.BoardRequest;
import com.example.demo.board.dto.BoardResponse;
import com.example.demo.global.response.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;


public interface BoardService {

    // 게시글 작성
    BoardResponse createBoard(BoardRequest request, Long userId, boolean isAdmin);

    BoardResponse createBoard(BoardRequest request, List<MultipartFile> files, Long userId, boolean isAdmin);

    // 게시글 단건 조회
    BoardResponse getBoard(Long boardId, Long userId, boolean isAdmin);

    // 게시글 전체 조회
    PageResponse<BoardResponse> getBoards(Pageable pageable, String category);

    // 게시글 수정
    BoardResponse updateBoard(Long boardId, BoardRequest request, Long userId, boolean isAdmin);

    BoardResponse updateBoard(Long boardId, BoardRequest request, List<MultipartFile> files, Long userId, boolean isAdmin);

    BoardResponse updateBoard(Long boardId, BoardRequest request, List<MultipartFile> files,
                              List<Long> deletedAttachmentIds, Long userId, boolean isAdmin);

    BoardFile getAttachment(Long boardId, Long attachmentId, Long userId, boolean isAdmin);

    // 게시글 삭제
    void deleteBoard(Long boardId, Long userId, boolean isAdmin);

    record BoardFile(String originalFilename, String contentType, byte[] content) {}

}
