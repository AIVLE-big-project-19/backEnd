package com.example.demo.notification.service;

import com.example.demo.board.entity.Board;
import com.example.demo.comment.entity.Comment;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.notification.entity.Notification;
import com.example.demo.notification.repository.NotificationRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.Role;
import com.example.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String NOTICE = "공지사항";
    private static final String INQUIRY = "1:1문의";

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    @Transactional
    public void notifyNewNotice(Board board) {
        if (!NOTICE.equals(board.getCategory())) return;
        userRepository.findAll().stream()
                .filter(user -> user.getRole() == Role.USER)
                .forEach(user -> notificationRepository.save(
                create(user, "NOTICE", "새 공지사항", board.getTitle(), "/boards/" + board.getBoardId())
                ));
    }

    @Transactional
    public void notifyNewInquiry(Board board) {
        if (!INQUIRY.equals(board.getCategory())) return;
        userRepository.findAll().stream()
                .filter(user -> user.getRole() == Role.ADMIN)
                .forEach(user -> notificationRepository.save(create(
                        user, "INQUIRY", "새 1:1 문의", "새로운 1:1 문의가 등록되었습니다.",
                        "/boards/" + board.getBoardId()
                )));
    }

    @Transactional
    public void notifyInquiryReply(Board board, Comment comment) {
        if (!INQUIRY.equals(board.getCategory())) return;
        User recipient = board.getAuthor();
        if (recipient == null && board.getWriter() != null) {
            recipient = userRepository.findByLoginId(board.getWriter()).orElse(null);
        }
        if (recipient == null || comment.getAuthor() == null || recipient.getId().equals(comment.getAuthor().getId())) return;
        notificationRepository.save(create(
                recipient, "INQUIRY_REPLY", "1:1 문의 답변", "작성하신 문의에 새 답변이 등록되었습니다.",
                "/boards/" + board.getBoardId()
        ));
    }

    @Transactional(readOnly = true)
    public NotificationSummary getNotifications(Long userId) {
        List<NotificationItem> items = notificationRepository.findTop5ByRecipientIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(item -> new NotificationItem(
                        item.getId(), item.getType(), item.getTitle(), item.getMessage(), item.getLink(),
                        item.getCreatedAt(), item.getReadAt() != null
                ))
                .toList();
        return new NotificationSummary(items, notificationRepository.countByRecipientIdAndReadAtIsNull(userId));
    }

    @Transactional
    public void markRead(Long notificationId, Long userId) {
        Notification notification = owned(notificationId, userId);
        notification.markRead();
    }

    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.findAllByRecipientIdAndReadAtIsNull(userId)
                .forEach(Notification::markRead);
    }

    @Transactional
    public void delete(Long notificationId, Long userId) {
        owned(notificationId, userId);
        notificationRepository.deleteByIdAndRecipientId(notificationId, userId);
    }

    @Transactional
    public void deleteAll(Long userId) {
        notificationRepository.deleteAllByRecipientId(userId);
    }

    private Notification owned(Long id, Long userId) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.BOARD_NOT_FOUND));
        if (!notification.getRecipient().getId().equals(userId)) {
            throw new CustomException(ErrorCode.BOARD_ACCESS_DENIED);
        }
        return notification;
    }

    private Notification create(User user, String type, String title, String message, String link) {
        return Notification.builder()
                .recipient(user)
                .type(type)
                .title(title)
                .message(message)
                .link(link)
                .build();
    }

    public record NotificationSummary(List<NotificationItem> items, long unreadCount) {}
    public record NotificationItem(Long id, String type, String title, String message, String link,
                                   java.time.LocalDateTime createdAt, boolean read) {}
}
