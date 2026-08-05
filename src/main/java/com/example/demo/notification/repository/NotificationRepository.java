package com.example.demo.notification.repository;

import com.example.demo.notification.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findTop5ByRecipientIdOrderByCreatedAtDesc(Long recipientId);
    List<Notification> findAllByRecipientIdAndReadAtIsNull(Long recipientId);
    void deleteByIdAndRecipientId(Long id, Long recipientId);
    void deleteAllByRecipientId(Long recipientId);
    long countByRecipientIdAndReadAtIsNull(Long recipientId);
}
