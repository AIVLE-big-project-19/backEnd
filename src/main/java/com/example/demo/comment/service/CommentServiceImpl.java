package com.example.demo.comment.service;

import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardRepository;
import com.example.demo.comment.dto.CommentRequest;
import com.example.demo.comment.dto.CommentResponse;
import com.example.demo.comment.entity.Comment;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;
    private final BoardRepository boardRepository;
    private final UserRepository userRepository;

    /**
     * 댓글 등록
     */
    @Override
    public CommentResponse createComment(Long boardId, Long userId, CommentRequest request) {

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.BOARD_NOT_FOUND));

        User author = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Comment comment = Comment.builder()
                .board(board)
                .writer(author.getLoginId())
                .author(author)
                .content(request.getContent())
                .build();

        Comment savedComment = commentRepository.save(comment);

        return entityToResponse(savedComment);
    }

    /**
     * 댓글 목록 조회
     */
    @Override
    public List<CommentResponse> getComments(Long boardId) {

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.BOARD_NOT_FOUND));

        return commentRepository.findByBoardOrderByCreatedAtAsc(board)
                .stream()
                .map(this::entityToResponse)
                .toList();
    }

    /**
     * 댓글 수정
     */
    @Override
    public CommentResponse updateComment(Long commentId, Long userId,
                                         CommentRequest request) {

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.COMMENT_NOT_FOUND));

        validateOwner(comment, userId);

        comment.setContent(request.getContent());

        Comment updatedComment = commentRepository.save(comment);

        return entityToResponse(updatedComment);
    }

    /**
     * 댓글 삭제
     */
    @Override
    public void deleteComment(Long commentId, Long userId) {

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.COMMENT_NOT_FOUND));

        validateOwner(comment, userId);

        commentRepository.delete(comment);

    }

    private void validateOwner(Comment comment, Long userId) {
        if (comment.getAuthor() == null || !comment.getAuthor().getId().equals(userId)) {
            throw new CustomException(ErrorCode.COMMENT_ACCESS_DENIED);
        }
    }

    /**
     * Entity → DTO
     */
    private CommentResponse entityToResponse(Comment comment){

        return CommentResponse.builder()
                .commentId(comment.getCommentId())
                .boardId(comment.getBoard().getBoardId())
                .writer(comment.getWriter())
                .content(comment.getContent())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .build();
    }

}
