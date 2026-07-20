package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.AdminUserResponse;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.RefreshTokenRepository;
import com.example.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(readOnly = true)
    public List<AdminUserResponse> getUsers() {
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getId))
                .map(AdminUserResponse::from)
                .toList();
    }

    @Transactional
    public AdminUserResponse changeRole(Long userId, Role role) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (user.getRole() == Role.ADMIN && role == Role.USER
                && userRepository.findAll().stream().filter(item -> item.getRole() == Role.ADMIN).count() <= 1) {
            throw new CustomException(ErrorCode.LAST_ADMIN_CANNOT_BE_DEMOTED);
        }

        user.setRole(role);
        User saved = userRepository.save(user);

        // 권한 변경 후 기존 refresh token으로 예전 권한의 JWT를 재발급하지 않도록 한다.
        refreshTokenRepository.deleteByUser(user);
        return AdminUserResponse.from(saved);
    }
}
