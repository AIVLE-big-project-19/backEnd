package com.example.demo.comment.service;

import com.example.demo.comment.dto.CommentRequest;
import com.example.demo.comment.dto.CommentResponse;

import java.util.List;

public interface CommentService {


    CommentResponse createComment(Long boardId, Long userId, boolean isAdmin,
                                  CommentRequest request);

    List<CommentResponse> getComments(Long boardId, Long userId, boolean isAdmin);


    CommentResponse updateComment(Long commentId, Long userId, boolean isAdmin,
                                  CommentRequest request);


    void deleteComment(Long commentId, Long userId, boolean isAdmin);

}
