package com.example.demo.comment.controller;

import com.example.demo.comment.dto.CommentRequest;
import com.example.demo.comment.dto.CommentResponse;
import com.example.demo.comment.service.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    /**
     * 댓글 등록
     */
    @PostMapping("/boards/{boardId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentResponse createComment(
            @PathVariable Long boardId,
            @Valid @RequestBody CommentRequest request) {

        return commentService.createComment(boardId, request);
    }

    /**
     * 댓글 목록 조회
     */
    @GetMapping("/boards/{boardId}/comments")
    public List<CommentResponse> getComments(
            @PathVariable Long boardId) {

        return commentService.getComments(boardId);
    }

    /**
     * 댓글 수정
     */
    @PutMapping("/comments/{commentId}")
    public CommentResponse updateComment(
            @PathVariable Long commentId,
            @Valid @RequestBody CommentRequest request) {

        return commentService.updateComment(commentId, request);
    }

    /**
     * 댓글 삭제
     */
    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(
            @PathVariable Long commentId) {

        commentService.deleteComment(commentId);
    }

}