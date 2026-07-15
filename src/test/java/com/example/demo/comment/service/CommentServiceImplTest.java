package com.example.demo.comment.service;

import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardRepository;
import com.example.demo.comment.dto.CommentRequest;
import com.example.demo.comment.entity.Comment;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommentServiceImplTest {

    @Mock
    private CommentRepository commentRepository;
    @Mock
    private BoardRepository boardRepository;
    @Mock
    private UserRepository userRepository;

    private CommentServiceImpl commentService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        commentService = new CommentServiceImpl(commentRepository, boardRepository, userRepository);
    }

    @Test
    void createCommentUsesAuthenticatedUserAsAuthor() {
        Board board = Board.builder().boardId(1L).build();
        User user = User.builder().id(10L).loginId("writer1").build();
        CommentRequest request = request("내용");

        when(boardRepository.findById(1L)).thenReturn(Optional.of(board));
        when(userRepository.findById(10L)).thenReturn(Optional.of(user));
        when(commentRepository.save(org.mockito.ArgumentMatchers.any(Comment.class)))
                .thenAnswer(invocation -> {
                    Comment comment = invocation.getArgument(0);
                    comment.setCommentId(100L);
                    return comment;
                });

        var response = commentService.createComment(1L, 10L, request);

        assertThat(response.getWriter()).isEqualTo("writer1");
    }

    @Test
    void ownerCanUpdateComment() {
        User owner = User.builder().id(10L).build();
        Comment comment = Comment.builder()
                .commentId(100L)
                .board(Board.builder().boardId(1L).build())
                .author(owner)
                .content("이전")
                .build();
        when(commentRepository.findById(100L)).thenReturn(Optional.of(comment));
        when(commentRepository.save(comment)).thenReturn(comment);

        commentService.updateComment(100L, 10L, request("수정됨"));

        assertThat(comment.getContent()).isEqualTo("수정됨");
    }

    @Test
    void anotherUserCannotUpdateComment() {
        Comment comment = Comment.builder()
                .commentId(100L)
                .author(User.builder().id(10L).build())
                .build();
        when(commentRepository.findById(100L)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.updateComment(100L, 20L, request("탈취")))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.COMMENT_ACCESS_DENIED);
        verify(commentRepository, never()).save(comment);
    }

    @Test
    void anotherUserCannotDeleteComment() {
        Comment comment = Comment.builder()
                .commentId(100L)
                .author(User.builder().id(10L).build())
                .build();
        when(commentRepository.findById(100L)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.deleteComment(100L, 20L))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.COMMENT_ACCESS_DENIED);
        verify(commentRepository, never()).delete(comment);
    }

    private CommentRequest request(String content) {
        CommentRequest request = new CommentRequest();
        request.setContent(content);
        return request;
    }
}
