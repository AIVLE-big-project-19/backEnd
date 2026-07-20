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
import org.springframework.security.core.Authentication;


@RestController
@RequestMapping("/boards")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BoardResponse> createBoard(
            @Valid @RequestBody BoardRequest request,
            Authentication authentication){

        return ApiResponse.success(
                SuccessCode.BOARD_CREATED,
                boardService.createBoard(request, userId(authentication), isAdmin(authentication))
        );

    }

    @GetMapping
    public ApiResponse<PageResponse<BoardResponse>> getBoards(
            Pageable pageable,
            @RequestParam(required = false) String category){

        return ApiResponse.success(
                SuccessCode.BOARD_LIST_FOUND,
                boardService.getBoards(pageable, category)
        );

    }

    @GetMapping("/{boardId}")
    public ApiResponse<BoardResponse> getBoard(
            @PathVariable Long boardId,
            Authentication authentication){

        return ApiResponse.success(
                SuccessCode.BOARD_FOUND,
                boardService.getBoard(boardId, userId(authentication), isAdmin(authentication))
        );

    }

    @PutMapping("/{boardId}")
    public ApiResponse<BoardResponse> updateBoard(
            @PathVariable Long boardId,
            @Valid @RequestBody BoardRequest request,
            Authentication authentication){

        return ApiResponse.success(
                SuccessCode.BOARD_UPDATED,
                boardService.updateBoard(boardId, request, userId(authentication), isAdmin(authentication))
        );

    }

    @DeleteMapping("/{boardId}")
    public ApiResponse<Void> deleteBoard(
            @PathVariable Long boardId,
            Authentication authentication){

        boardService.deleteBoard(boardId, userId(authentication), isAdmin(authentication));

        return ApiResponse.success(
                SuccessCode.BOARD_DELETED
        );

    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private Long userId(Authentication authentication) {
        return authentication == null ? null : (Long) authentication.getPrincipal();
    }

}
