package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.SignupRequest;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        userService = new UserService(userRepository, emailVerificationService, passwordEncoder);
    }

    @Test
    void 사용_가능한_아이디면_true를_반환한다() {
        when(userRepository.existsByLoginId("newid")).thenReturn(false);

        assertThat(userService.checkLoginIdAvailable("newid")).isTrue();
    }

    @Test
    void 이메일_인증이_안됐으면_회원가입시_예외가_발생한다() {
        SignupRequest request = validRequest();
        when(emailVerificationService.isVerified(request.getEmail())).thenReturn(false);

        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
    }

    @Test
    void 이미_사용중인_아이디면_예외가_발생한다() {
        SignupRequest request = validRequest();
        when(emailVerificationService.isVerified(request.getEmail())).thenReturn(true);
        when(userRepository.existsByLoginId(request.getLoginId())).thenReturn(true);

        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_LOGIN_ID);
    }

    @Test
    void 이미_가입된_이메일이면_예외가_발생한다() {
        SignupRequest request = validRequest();
        when(emailVerificationService.isVerified(request.getEmail())).thenReturn(true);
        when(userRepository.existsByLoginId(request.getLoginId())).thenReturn(false);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(true);

        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    void 정상_요청이면_비밀번호를_인코딩해서_저장하고_인증플래그를_지운다() {
        SignupRequest request = validRequest();
        when(emailVerificationService.isVerified(request.getEmail())).thenReturn(true);
        when(userRepository.existsByLoginId(request.getLoginId())).thenReturn(false);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("ENCODED");

        userService.signup(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getPassword()).isEqualTo("ENCODED");
        assertThat(saved.getProvider()).isEqualTo(Provider.LOCAL);
        assertThat(saved.getRole()).isEqualTo(Role.USER);
        verify(emailVerificationService).clearVerified(request.getEmail());
    }

    private SignupRequest validRequest() {
        SignupRequest request = new SignupRequest();
        request.setLoginId("tester01");
        request.setEmail("tester01@example.com");
        request.setPassword("password123");
        request.setName("테스터");
        return request;
    }
}
