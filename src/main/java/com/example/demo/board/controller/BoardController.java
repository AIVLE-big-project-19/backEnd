package com.example.demo.board.controller;

import com.example.demo.board.dto.BoardRequest;
import com.example.demo.board.dto.BoardResponse;
import com.example.demo.board.service.BoardService;
import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.PageResponse;
import com.example.demo.global.response.SuccessCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;


@RestController
@RequestMapping("/boards")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BoardResponse> createBoard(
            @Valid @RequestBody BoardRequest request){

        return ApiResponse.success(
                SuccessCode.BOARD_CREATED,
                boardService.createBoard(request)
        );

    }

    @GetMapping
    public ApiResponse<PageResponse<BoardResponse>> getBoards(
            Pageable pageable){

        return ApiResponse.success(
                SuccessCode.BOARD_LIST_FOUND,
                boardService.getBoards(pageable)
        );

    }

    @GetMapping("/{boardId}")
    public ApiResponse<BoardResponse> getBoard(
            @PathVariable Long boardId){

        return ApiResponse.success(
                SuccessCode.BOARD_FOUND,
                boardService.getBoard(boardId)
        );

    }

    @PutMapping("/{boardId}")
    public ApiResponse<BoardResponse> updateBoard(
            @PathVariable Long boardId,
            @Valid @RequestBody BoardRequest request){

        return ApiResponse.success(
                SuccessCode.BOARD_UPDATED,
                boardService.updateBoard(boardId, request)
        );

    }

    @DeleteMapping("/{boardId}")
    public ApiResponse<Void> deleteBoard(
            @PathVariable Long boardId){

        boardService.deleteBoard(boardId);

        return ApiResponse.success(
                SuccessCode.BOARD_DELETED
        );

    }

}