package com.example.demo.user.service;

import com.example.demo.global.util.EmailHasher;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class PiiMigrationService {

    private final UserRepository userRepository;

    @Transactional
    public void migratePendingUsers() {
        List<User> pending = userRepository.findByEmailHashIsNull();
        if (pending.isEmpty()) {
            return;
        }

        for (User user : pending) {
            user.setEmailHash(EmailHasher.hash(user.getEmail()));
        }

        userRepository.saveAll(pending);
    }
}
