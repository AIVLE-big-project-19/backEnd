package com.example.demo.user.service;

import com.example.demo.board.dto.BoardResponse;
import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardRepository;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.MyPageResponse;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.RefreshTokenRepository;
import com.example.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MyPageService {
    private final UserRepository userRepository;
    private final BoardRepository boardRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public MyPageResponse getMyProfile(Long userId) {
        return toProfile(findUser(userId));
    }

    @Transactional
    public MyPageResponse updateMyProfile(Long userId, String name) {
        User user = findUser(userId);
        user.setName(name.trim());
        return toProfile(user);
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = findUser(userId);
        if (user.getProvider() != Provider.LOCAL || user.getPassword() == null
                || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        refreshTokenRepository.deleteByUser(user);
    }

    @Transactional(readOnly = true)
    public List<BoardResponse> getMyBoards(Long userId) {
        User user = findUser(userId);
        return boardRepository.findByWriterOrderByCreatedAtDesc(user.getLoginId())
                .stream()
                .map(this::toBoardResponse)
                .toList();
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    private MyPageResponse toProfile(User user) {
        return MyPageResponse.builder()
                .loginId(user.getLoginId())
                .email(user.getEmail())
                .name(user.getName())
                .provider(user.getProvider())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private BoardResponse toBoardResponse(Board board) {
        return BoardResponse.builder()
                .boardId(board.getBoardId())
                .title(board.getTitle())
                .content(board.getContent())
                .writer(board.getWriter())
                .category(board.getCategory())
                .viewCount(board.getViewCount())
                .createdAt(board.getCreatedAt())
                .updatedAt(board.getUpdatedAt())
                .build();
    }
}
