package com.example.demo.board.service;

import com.example.demo.board.dto.BoardRequest;
import com.example.demo.board.dto.BoardResponse;
import com.example.demo.global.response.PageResponse;
import org.springframework.data.domain.Pageable;


public interface BoardService {

    // 게시글 작성
    BoardResponse createBoard(BoardRequest request);

    // 게시글 단건 조회
    BoardResponse getBoard(Long boardId);

    // 게시글 전체 조회
    PageResponse<BoardResponse> getBoards(Pageable pageable);

    // 게시글 수정
    BoardResponse updateBoard(Long boardId, BoardRequest request);

    // 게시글 삭제
    void deleteBoard(Long boardId);

}