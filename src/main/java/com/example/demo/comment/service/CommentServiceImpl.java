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

    private static final String NOTICE = "공지사항";
    private static final String FAQ = "FAQ";
    private static final String INQUIRY = "1:1문의";

    private final CommentRepository commentRepository;
    private final BoardRepository boardRepository;
    private final UserRepository userRepository;

    /**
     * 댓글 등록
     */
    @Override
    public CommentResponse createComment(Long boardId, Long userId, boolean isAdmin, CommentRequest request) {

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.BOARD_NOT_FOUND));

        validateCommentsAllowed(board);
        validateInquiryCommentPermission(board, isAdmin);

        User author = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Comment comment = Comment.builder()
                .board(board)
                .writer(resolveWriterKey(author))
                .author(author)
                .content(request.getContent())
                .secret(isFree(board) && Boolean.TRUE.equals(request.getSecret()))
                .build();

        Comment savedComment = commentRepository.save(comment);

        return entityToResponse(savedComment, userId, isAdmin);
    }

    /**
     * 댓글 목록 조회
     */
    @Override
    public List<CommentResponse> getComments(Long boardId, Long userId, boolean isAdmin) {

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.BOARD_NOT_FOUND));

        validateCommentsAllowed(board);

        return commentRepository.findByBoardOrderByCreatedAtAsc(board)
                .stream()
                .map(comment -> entityToResponse(comment, userId, isAdmin))
                .toList();
    }

    /**
     * 댓글 수정
     */
    @Override
    public CommentResponse updateComment(Long commentId, Long userId, boolean isAdmin,
                                         CommentRequest request) {

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.COMMENT_NOT_FOUND));

        validateCommentsAllowed(comment.getBoard());
        validateMutationPermission(comment, userId, isAdmin);

        comment.setContent(request.getContent());
        if (isFree(comment.getBoard()) && request.getSecret() != null) {
            comment.setSecret(request.getSecret());
        }

        Comment updatedComment = commentRepository.save(comment);

        return entityToResponse(updatedComment, userId, isAdmin);
    }

    /**
     * 댓글 삭제
     */
    @Override
    public void deleteComment(Long commentId, Long userId, boolean isAdmin) {

        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.COMMENT_NOT_FOUND));

        validateCommentsAllowed(comment.getBoard());
        validateMutationPermission(comment, userId, isAdmin);

        commentRepository.delete(comment);

    }

    private void validateMutationPermission(Comment comment, Long userId, boolean isAdmin) {
        if (isInquiry(comment.getBoard())) {
            validateInquiryCommentPermission(comment.getBoard(), isAdmin);
            return;
        }
        validateOwner(comment, userId);
    }

    private void validateOwner(Comment comment, Long userId) {
        if (comment.getAuthor() == null || !comment.getAuthor().getId().equals(userId)) {
            throw new CustomException(ErrorCode.COMMENT_ACCESS_DENIED);
        }
    }

    /**
     * Entity → DTO
     */
    private CommentResponse entityToResponse(Comment comment, Long viewerId, boolean isAdmin){
        boolean canView = canView(comment, viewerId, isAdmin);

        return CommentResponse.builder()
                .commentId(comment.getCommentId())
                .boardId(comment.getBoard().getBoardId())
                .writer(comment.getWriter())
                .writerName(resolveWriterName(comment))
                .owner(comment.getAuthor() != null && viewerId != null
                        && viewerId.equals(comment.getAuthor().getId()))
                .content(canView ? comment.getContent() : "비밀댓글입니다.")
                .secret(Boolean.TRUE.equals(comment.getSecret()))
                .canView(canView)
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .build();
    }

    private boolean canView(Comment comment, Long viewerId, boolean isAdmin) {
        if (!Boolean.TRUE.equals(comment.getSecret())) {
            return true;
        }
        if (isAdmin) {
            return true;
        }
        if (viewerId == null) {
            return false;
        }
        if (comment.getAuthor() != null && viewerId.equals(comment.getAuthor().getId())) {
            return true;
        }
        return userRepository.findById(viewerId)
                .map(user -> user.getLoginId() != null
                        && user.getLoginId().equals(comment.getBoard().getWriter()))
                .orElse(false);
    }

    private void validateCommentsAllowed(Board board) {
        if (NOTICE.equals(board.getCategory()) || FAQ.equals(board.getCategory())) {
            throw new CustomException(ErrorCode.COMMENTS_NOT_ALLOWED);
        }
    }

    private void validateInquiryCommentPermission(Board board, boolean isAdmin) {
        if (isInquiry(board) && !isAdmin) {
            throw new CustomException(ErrorCode.INQUIRY_COMMENT_ADMIN_ONLY);
        }
    }

    private boolean isInquiry(Board board) {
        return INQUIRY.equals(board.getCategory());
    }

    private boolean isFree(Board board) {
        return "자유게시판".equals(board.getCategory());
    }

    private String resolveWriterName(Comment comment) {
        if (comment.getAuthor() != null && comment.getAuthor().getName() != null
                && !comment.getAuthor().getName().isBlank()) {
            return comment.getAuthor().getName();
        }
        if (comment.getWriter() == null) {
            return "탈퇴한 회원";
        }
        return userRepository.findByLoginId(comment.getWriter())
                .map(User::getName)
                .filter(name -> !name.isBlank())
                .orElse(comment.getWriter());
    }

    private String resolveWriterKey(User user) {
        if (user.getLoginId() != null && !user.getLoginId().isBlank()) {
            return limitWriterLength(user.getLoginId());
        }
        if (user.getName() != null && !user.getName().isBlank()) {
            return limitWriterLength(user.getName());
        }
        return limitWriterLength(user.getEmail());
    }

    private String limitWriterLength(String value) {
        if (value == null || value.isBlank()) {
            return "알 수 없는 회원";
        }
        return value.length() <= 30 ? value : value.substring(0, 30);
    }

}
