package com.example.demo.comment.service;

import com.example.demo.comment.dto.CommentRequest;
import com.example.demo.comment.dto.CommentResponse;

import java.util.List;

public interface CommentService {


    CommentResponse createComment(Long boardId, Long userId,
                                  CommentRequest request);

    List<CommentResponse> getComments(Long boardId);


    CommentResponse updateComment(Long commentId, Long userId,
                                  CommentRequest request);


    void deleteComment(Long commentId, Long userId);

}
