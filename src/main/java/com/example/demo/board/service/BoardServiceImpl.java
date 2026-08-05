package com.example.demo.board.service;

import com.example.demo.board.dto.BoardRequest;
import com.example.demo.board.dto.BoardResponse;
import com.example.demo.board.dto.BoardAttachmentResponse;
import com.example.demo.board.entity.BoardAttachment;
import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardAttachmentRepository;
import com.example.demo.board.repository.BoardRepository;
import com.example.demo.board.storage.BoardFileStorage;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.global.response.PageResponse;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BoardServiceImpl implements BoardService {

    private static final int MAX_ATTACHMENT_COUNT = 10;
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final long MAX_TOTAL_ATTACHMENT_SIZE = 50L * 1024 * 1024;

    private static final String NOTICE = "공지사항";
    private static final String FAQ = "FAQ";
    private static final String INQUIRY = "1:1문의";

    private final BoardRepository boardRepository;
    private final UserRepository userRepository;
    private final BoardAttachmentRepository boardAttachmentRepository;
    private final BoardFileStorage boardFileStorage;
    private final MailSender mailSender;

    @Value("${app.admin-email}")
    private String adminEmail;

    @Override
    public BoardResponse createBoard(BoardRequest request, Long userId, boolean isAdmin) {
        return createBoard(request, List.of(), userId, isAdmin);
    }

    @Override
    @Transactional
    public BoardResponse createBoard(BoardRequest request, List<MultipartFile> files, Long userId, boolean isAdmin) {
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
        validateAttachmentLimits(savedBoard, files, List.of());
        storeAttachments(savedBoard, files);

        if (isInquiry(savedBoard)) {
            notifyAdminOfNewInquiry(savedBoard);
        }

        return entityToResponse(savedBoard, userId);
    }

    private void notifyAdminOfNewInquiry(Board board) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(adminEmail.split("\\s*,\\s*"));
        message.setSubject("[1:1문의] " + board.getTitle());
        message.setText(resolveWriterName(board) + "님이 문의를 남겼습니다.\n\n" + board.getContent());
        mailSender.send(message);
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
        return getBoards(pageable, category, null, false);
    }

    @Override
    public PageResponse<BoardResponse> getBoards(Pageable pageable, String category, Long userId, boolean isAdmin){

        Pageable newestFirst = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        Page<Board> boards;
        if (INQUIRY.equals(category) && !isAdmin) {
            if (userId == null) {
                boards = Page.empty(newestFirst);
            } else {
                User user = getUser(userId);
                boards = boardRepository.findByCategoryAndOwner(category, user, resolveWriterKey(user), newestFirst);
            }
        } else if (category == null || category.isBlank()) {
            boards = boardRepository.findAll(newestFirst);
        } else {
            boards = boardRepository.findByCategory(category, newestFirst);
        }

        return PageResponse.from(boards.map(board -> entityToResponse(board, userId)));
    }

    @Override
    public BoardResponse updateBoard(Long boardId, BoardRequest request, Long userId, boolean isAdmin) {
        return updateBoard(boardId, request, List.of(), userId, isAdmin);
    }

    @Override
    @Transactional
    public BoardResponse updateBoard(Long boardId, BoardRequest request, List<MultipartFile> files, Long userId, boolean isAdmin) {
        return updateBoard(boardId, request, files, List.of(), userId, isAdmin);
    }

    @Override
    @Transactional
    public BoardResponse updateBoard(Long boardId, BoardRequest request, List<MultipartFile> files,
                                     List<Long> deletedAttachmentIds, Long userId, boolean isAdmin) {

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

        validateAttachmentLimits(board, files, deletedAttachmentIds);
        Board updatedBoard = boardRepository.save(board);
        deleteAttachments(updatedBoard, deletedAttachmentIds);
        storeAttachments(updatedBoard, files);

        return entityToResponse(updatedBoard, userId);
    }

    @Override
    @Transactional
    public void deleteBoard(Long boardId, Long userId, boolean isAdmin) {

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.BOARD_NOT_FOUND));

        if (isAdminCategory(board.getCategory())) {
            validateAdminCategory(board.getCategory(), isAdmin);
        } else if (!isOwner(board, userId) && !(isInquiry(board) && isAdmin)) {
            throw new CustomException(ErrorCode.BOARD_OWNER_ONLY);
        }

        board.getAttachments().forEach(attachment -> deleteStoredFile(attachment.getStoredFilename()));
        boardRepository.delete(board);

    }

    @Override
    @Transactional(readOnly = true)
    public BoardFile getAttachment(Long boardId, Long attachmentId, Long userId, boolean isAdmin) {
        Board board = boardRepository.findById(boardId)
                .orElseThrow(() -> new CustomException(ErrorCode.BOARD_NOT_FOUND));
        if (isInquiry(board) && !isAdmin && !isOwner(board, userId)) {
            throw new CustomException(ErrorCode.BOARD_ACCESS_DENIED);
        }
        BoardAttachment attachment = boardAttachmentRepository.findById(attachmentId)
                .filter(item -> item.getBoard().getBoardId().equals(boardId))
                .orElseThrow(() -> new CustomException(ErrorCode.BOARD_NOT_FOUND));
        return new BoardFile(
                attachment.getOriginalFilename(),
                attachment.getContentType(),
                boardFileStorage.download(attachment.getStoredFilename())
        );
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
                .attachments(board.getAttachments().stream().map(attachment -> BoardAttachmentResponse.builder()
                        .attachmentId(attachment.getAttachmentId())
                        .originalFilename(attachment.getOriginalFilename())
                        .contentType(attachment.getContentType())
                        .fileSize(attachment.getFileSize())
                        .image(attachment.getContentType() != null && attachment.getContentType().startsWith("image/"))
                        .build()).toList())
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

    private void storeAttachments(Board board, List<MultipartFile> files) {
        if (files == null) return;
        List<String> uploadedKeys = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) continue;
                String contentType = file.getContentType();
                if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
                String originalFilename = file.getOriginalFilename();
                if (originalFilename == null || originalFilename.isBlank() || file.getSize() > MAX_FILE_SIZE) {
                    throw new CustomException(ErrorCode.INVALID_INPUT);
                }
                String objectKey = boardFileStorage.upload(file, contentType);
                uploadedKeys.add(objectKey);
                BoardAttachment attachment = boardAttachmentRepository.save(BoardAttachment.builder()
                        .board(board).originalFilename(originalFilename).storedFilename(objectKey)
                        .contentType(contentType).fileSize(file.getSize()).build());
                board.getAttachments().add(attachment);
            }
        } catch (RuntimeException exception) {
            uploadedKeys.forEach(this::deleteStoredFileQuietly);
            throw exception;
        }
    }

    private void deleteAttachments(Board board, List<Long> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) return;
        for (Long attachmentId : attachmentIds) {
            if (attachmentId == null) continue;
            BoardAttachment attachment = boardAttachmentRepository.findById(attachmentId)
                    .filter(item -> item.getBoard().getBoardId().equals(board.getBoardId()))
                    .orElseThrow(() -> new CustomException(ErrorCode.BOARD_NOT_FOUND));
            deleteStoredFile(attachment.getStoredFilename());
            board.getAttachments().remove(attachment);
            boardAttachmentRepository.delete(attachment);
        }
    }

    private void validateAttachmentLimits(Board board, List<MultipartFile> files, List<Long> deletedAttachmentIds) {
        List<MultipartFile> newFiles = files == null ? List.of() : files.stream()
                .filter(file -> file != null && !file.isEmpty()).toList();
        for (MultipartFile file : newFiles) {
            if (file.getOriginalFilename() == null || file.getOriginalFilename().isBlank() || file.getSize() > MAX_FILE_SIZE) {
                throw new CustomException(ErrorCode.INVALID_INPUT);
            }
        }
        List<Long> deletedIds = deletedAttachmentIds == null ? List.of() : deletedAttachmentIds;
        long existingSize = board.getAttachments().stream()
                .filter(attachment -> !deletedIds.contains(attachment.getAttachmentId()))
                .mapToLong(BoardAttachment::getFileSize)
                .sum();
        long newSize = newFiles.stream().mapToLong(MultipartFile::getSize).sum();
        long existingCount = board.getAttachments().stream()
                .filter(attachment -> !deletedIds.contains(attachment.getAttachmentId())).count();
        if (existingCount + newFiles.size() > MAX_ATTACHMENT_COUNT || existingSize + newSize > MAX_TOTAL_ATTACHMENT_SIZE) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
    }

    private void deleteStoredFile(String storedFilename) {
        boardFileStorage.delete(storedFilename);
    }

    private void deleteStoredFileQuietly(String storedFilename) {
        try {
            boardFileStorage.delete(storedFilename);
        } catch (RuntimeException ignored) {
            // 원래 업로드/DB 오류가 사용자에게 전달되도록 정리 실패는 무시한다.
        }
    }

}
