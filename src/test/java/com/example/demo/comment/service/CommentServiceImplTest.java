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
import java.util.List;

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

        var response = commentService.createComment(1L, 10L, false, request);

        assertThat(response.getWriter()).isEqualTo("writer1");
    }

    @Test
    void googleUserCanCreateCommentWithoutLoginId() {
        Board board = Board.builder().boardId(1L).category("자유게시판").build();
        User googleUser = User.builder()
                .id(10L)
                .name("구글 사용자")
                .email("google@example.com")
                .build();
        when(boardRepository.findById(1L)).thenReturn(Optional.of(board));
        when(userRepository.findById(10L)).thenReturn(Optional.of(googleUser));
        when(commentRepository.save(org.mockito.ArgumentMatchers.any(Comment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = commentService.createComment(1L, 10L, false, request("내용"));

        assertThat(response.getWriter()).isEqualTo("구글 사용자");
        assertThat(response.getWriterName()).isEqualTo("구글 사용자");
        assertThat(response.getOwner()).isTrue();
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

        commentService.updateComment(100L, 10L, false, request("수정됨"));

        assertThat(comment.getContent()).isEqualTo("수정됨");
    }

    @Test
    void anotherUserCannotUpdateComment() {
        Comment comment = Comment.builder()
                .commentId(100L)
                .board(Board.builder().boardId(1L).category("자유게시판").build())
                .author(User.builder().id(10L).build())
                .build();
        when(commentRepository.findById(100L)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.updateComment(100L, 20L, false, request("탈취")))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.COMMENT_ACCESS_DENIED);
        verify(commentRepository, never()).save(comment);
    }

    @Test
    void anotherUserCannotDeleteComment() {
        Comment comment = Comment.builder()
                .commentId(100L)
                .board(Board.builder().boardId(1L).category("자유게시판").build())
                .author(User.builder().id(10L).build())
                .build();
        when(commentRepository.findById(100L)).thenReturn(Optional.of(comment));

        assertThatThrownBy(() -> commentService.deleteComment(100L, 20L, false))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.COMMENT_ACCESS_DENIED);
        verify(commentRepository, never()).delete(comment);
    }

    @Test
    void FAQ에는_댓글을_작성할_수_없다() {
        Board board = Board.builder().boardId(1L).category("FAQ").build();
        when(boardRepository.findById(1L)).thenReturn(Optional.of(board));

        assertThatThrownBy(() -> commentService.createComment(1L, 10L, true, request("답변")))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.COMMENTS_NOT_ALLOWED);
        verify(commentRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 일반회원은_문의글에_댓글을_작성할_수_없다() {
        Board board = Board.builder().boardId(1L).category("1:1문의").build();
        when(boardRepository.findById(1L)).thenReturn(Optional.of(board));

        assertThatThrownBy(() -> commentService.createComment(1L, 10L, false, request("답변")))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.INQUIRY_COMMENT_ADMIN_ONLY);
    }

    @Test
    void 비밀댓글은_관계없는_회원에게_내용을_가린다() {
        Board board = Board.builder().boardId(1L).writer("boardOwner").category("자유게시판").build();
        Comment comment = Comment.builder()
                .commentId(100L)
                .board(board)
                .author(User.builder().id(10L).build())
                .writer("commentOwner")
                .content("비밀 내용")
                .secret(true)
                .build();
        when(boardRepository.findById(1L)).thenReturn(Optional.of(board));
        when(commentRepository.findByBoardOrderByCreatedAtAsc(board)).thenReturn(List.of(comment));
        when(userRepository.findById(20L)).thenReturn(Optional.of(user(20L, "other")));

        var response = commentService.getComments(1L, 20L, false).get(0);

        assertThat(response.getContent()).isEqualTo("비밀댓글입니다.");
        assertThat(response.getCanView()).isFalse();
        assertThat(response.getSecret()).isTrue();
    }

    private User user(Long id, String loginId) {
        return User.builder().id(id).loginId(loginId).build();
    }

    private CommentRequest request(String content) {
        CommentRequest request = new CommentRequest();
        request.setContent(content);
        return request;
    }
}
