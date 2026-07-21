package com.example.demo.board.service;

import com.example.demo.board.dto.BoardRequest;
import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardRepository;
import com.example.demo.board.repository.BoardAttachmentRepository;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BoardServiceImplTest {

    @Mock BoardRepository boardRepository;
    @Mock UserRepository userRepository;
    @Mock BoardAttachmentRepository boardAttachmentRepository;
    private BoardServiceImpl boardService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        boardService = new BoardServiceImpl(boardRepository, userRepository, boardAttachmentRepository);
    }

    @Test
    void 일반회원은_FAQ를_작성할_수_없다() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "member")));

        assertThatThrownBy(() -> boardService.createBoard(request("FAQ"), 1L, false))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.ADMIN_BOARD_ONLY);

        verify(boardRepository, never()).save(any());
    }

    @Test
    void 작성자는_요청값이_아닌_인증사용자로_저장된다() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "realWriter")));
        when(boardRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        BoardRequest request = request("자유게시판");
        request.setWriter("spoofedWriter");

        var response = boardService.createBoard(request, 1L, false);

        assertThat(response.getWriter()).isEqualTo("realWriter");
    }

    @Test
    void 구글회원은_로그인아이디가_없어도_이름으로_게시글을_작성한다() {
        User googleUser = User.builder()
                .id(2L)
                .loginId(null)
                .name("구글 사용자")
                .email("google@example.com")
                .build();
        when(userRepository.findById(2L)).thenReturn(Optional.of(googleUser));
        when(boardRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = boardService.createBoard(request("자유게시판"), 2L, false);

        assertThat(response.getWriter()).isEqualTo("구글 사용자");
        assertThat(response.getWriterName()).isEqualTo("구글 사용자");
        assertThat(response.getOwner()).isTrue();
    }

    @Test
    void 다른회원은_문의글_상세에_접근할_수_없다() {
        Board board = Board.builder().boardId(10L).writer("owner").category("1:1문의").build();
        when(boardRepository.findById(10L)).thenReturn(Optional.of(board));
        when(userRepository.findById(2L)).thenReturn(Optional.of(user(2L, "other")));

        assertThatThrownBy(() -> boardService.getBoard(10L, 2L, false))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.BOARD_ACCESS_DENIED);
    }

    @Test
    void 관리자는_문의글을_삭제할_수_있지만_수정할_수_없다() {
        Board board = Board.builder().boardId(10L).writer("owner").category("1:1문의").build();
        when(boardRepository.findById(10L)).thenReturn(Optional.of(board));

        boardService.deleteBoard(10L, 99L, true);
        verify(boardRepository).delete(board);

        assertThatThrownBy(() -> boardService.updateBoard(10L, request("1:1문의"), 99L, true))
                .isInstanceOf(CustomException.class)
                .extracting(error -> ((CustomException) error).getErrorCode())
                .isEqualTo(ErrorCode.INQUIRY_ADMIN_CANNOT_UPDATE);
    }

    private User user(Long id, String loginId) {
        return User.builder().id(id).loginId(loginId).build();
    }

    private BoardRequest request(String category) {
        BoardRequest request = new BoardRequest();
        request.setTitle("제목");
        request.setContent("내용");
        request.setWriter("writer");
        request.setCategory(category);
        return request;
    }
}
