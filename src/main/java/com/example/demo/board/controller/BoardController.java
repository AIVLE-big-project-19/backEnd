package com.example.demo.board.controller;

import com.example.demo.board.dto.BoardRequest;
import com.example.demo.board.dto.BoardResponse;
import com.example.demo.board.service.BoardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/boards")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BoardResponse createBoard(@Valid @RequestBody BoardRequest request){

        return boardService.createBoard(request);
    }

    @GetMapping
    public List<BoardResponse> getBoards(){

        return boardService.getBoards();
    }

    @GetMapping("/{boardId}")
    public BoardResponse getBoard(@PathVariable Long boardId){

        return boardService.getBoard(boardId);
    }

    @PutMapping("/{boardId}")
    public BoardResponse updateBoard(
            @PathVariable Long boardId,
            @Valid @RequestBody BoardRequest request){

        return boardService.updateBoard(boardId, request);
    }

    @DeleteMapping("/{boardId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBoard(@PathVariable Long boardId){

        boardService.deleteBoard(boardId);

    }

}