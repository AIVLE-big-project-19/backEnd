package com.example.demo.board.service;

import com.example.demo.board.dto.BoardRequest;
import com.example.demo.board.dto.BoardResponse;
import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardRepository;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.global.response.PageResponse;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@Service
@RequiredArgsConstructor
public class BoardServiceImpl implements BoardService {

    private static final String NOTICE = "공지사항";
    private static final String FAQ = "FAQ";
    private static final String INQUIRY = "1:1문의";

    private final BoardRepository boardRepository;
    private final UserRepository userRepository;

    @Override
    public BoardResponse createBoard(BoardRequest request, Long userId, boolean isAdmin) {
        User user = getUser(userId);
        validateAdminCategory(request.getCategory(), isAdmin);

        Board board = Board.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .writer(resolveWriterKey(user))
                .author(user)
                .category(request.getCategory())
                .build();

        Board savedBoard = boardRepository.save(board);

        return entityToResponse(savedBoard, userId);
    }

    @Override
    public BoardResponse getBoard(Long boardId, Long userId, boolean isAdmin) {

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.BOARD_NOT_FOUND));

        if (isInquiry(board) && !isAdmin && !isOwner(board, userId)) {
            throw new CustomException(ErrorCode.BOARD_ACCESS_DENIED);
        }

        board.increaseViewCount();
        boardRepository.save(board);

        return entityToResponse(board, userId);
    }

    @Override
    public PageResponse<BoardResponse> getBoards(Pageable pageable, String category){

        Pageable newestFirst = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return PageResponse.from(
                category == null || category.isBlank()
                        ? boardRepository.findAll(newestFirst).map(this::entityToResponse)
                        : boardRepository.findByCategory(category, newestFirst)
                        .map(this::entityToResponse)
        );
    }

    @Override
    public BoardResponse updateBoard(Long boardId, BoardRequest request, Long userId, boolean isAdmin) {

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.BOARD_NOT_FOUND));

        if (isInquiry(board) && isAdmin) {
            throw new CustomException(ErrorCode.INQUIRY_ADMIN_CANNOT_UPDATE);
        }
        if (isAdminCategory(board.getCategory())) {
            validateAdminCategory(board.getCategory(), isAdmin);
        } else if (!isOwner(board, userId)) {
            throw new CustomException(ErrorCode.BOARD_OWNER_ONLY);
        }
        validateAdminCategory(request.getCategory(), isAdmin);

        board.update(
                request.getTitle(),
                request.getContent(),
                request.getCategory()
        );

        Board updatedBoard = boardRepository.save(board);

        return entityToResponse(updatedBoard, userId);
    }

    @Override
    public void deleteBoard(Long boardId, Long userId, boolean isAdmin) {

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.BOARD_NOT_FOUND));

        if (isAdminCategory(board.getCategory())) {
            validateAdminCategory(board.getCategory(), isAdmin);
        } else if (!isOwner(board, userId) && !(isInquiry(board) && isAdmin)) {
            throw new CustomException(ErrorCode.BOARD_OWNER_ONLY);
        }

        boardRepository.delete(board);

    }

    private void validateAdminCategory(String category, boolean isAdmin) {
        if (isAdminCategory(category) && !isAdmin) {
            throw new CustomException(ErrorCode.ADMIN_BOARD_ONLY);
        }
    }

    private boolean isAdminCategory(String category) {
        return NOTICE.equals(category) || FAQ.equals(category);
    }

    private boolean isInquiry(Board board) {
        return INQUIRY.equals(board.getCategory());
    }

    private boolean isOwner(Board board, Long userId) {
        if (userId == null) {
            return false;
        }
        if (board.getAuthor() != null) {
            return userId.equals(board.getAuthor().getId());
        }
        return userRepository.findById(userId)
                .map(user -> matchesLegacyWriter(user, board.getWriter()))
                .orElse(false);
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * Entity → DTO 변환
     */
    private BoardResponse entityToResponse(Board board){
        return entityToResponse(board, null);
    }

    private BoardResponse entityToResponse(Board board, Long viewerId){

        return BoardResponse.builder()
                .boardId(board.getBoardId())
                .title(board.getTitle())
                .content(board.getContent())
                .writer(board.getWriter())
                .writerName(resolveWriterName(board))
                .owner(viewerId != null && isOwner(board, viewerId))
                .category(board.getCategory())
                .viewCount(board.getViewCount())
                .createdAt(board.getCreatedAt())
                .updatedAt(board.getUpdatedAt())
                .build();

    }

    private String resolveWriterName(Board board) {
        return resolveWriterName(board.getAuthor(), board.getWriter());
    }

    private String resolveWriterName(User author, String writer) {
        if (author != null && author.getName() != null && !author.getName().isBlank()) {
            return author.getName();
        }
        if (writer == null) {
            return "탈퇴한 회원";
        }
        return userRepository.findByLoginId(writer)
                .map(User::getName)
                .filter(name -> !name.isBlank())
                .orElse(writer);
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

    private boolean matchesLegacyWriter(User user, String writer) {
        if (writer == null) {
            return false;
        }
        return writer.equals(user.getLoginId())
                || writer.equals(user.getEmail())
                || writer.equals(user.getName());
    }

}
