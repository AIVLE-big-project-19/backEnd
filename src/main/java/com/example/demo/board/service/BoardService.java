package com.example.demo.board.service;

import com.example.demo.board.dto.BoardRequest;
import com.example.demo.board.dto.BoardResponse;
import com.example.demo.global.response.PageResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;


public interface BoardService {

    BoardResponse createBoard(BoardRequest request, List<MultipartFile> files, Long userId, boolean isAdmin);

    BoardResponse getBoard(Long boardId, Long userId, boolean isAdmin);

    PageResponse<BoardResponse> getBoards(Pageable pageable, String category, Long userId, boolean isAdmin);

    BoardResponse togglePinned(Long boardId, boolean isAdmin);

    BoardResponse updateBoard(Long boardId, BoardRequest request, List<MultipartFile> files,
                              List<Long> deletedAttachmentIds, Long userId, boolean isAdmin);

    BoardFile getAttachment(Long boardId, Long attachmentId, Long userId, boolean isAdmin);

    void deleteBoard(Long boardId, Long userId, boolean isAdmin);

    record BoardFile(String originalFilename, String contentType, byte[] content) {}

}
