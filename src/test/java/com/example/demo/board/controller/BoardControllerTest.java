package com.example.demo.board.controller;

import com.example.demo.board.dto.BoardRequest;
import com.example.demo.board.service.BoardService;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BoardControllerTest {

    @Mock
    private BoardService boardService;

    @InjectMocks
    private BoardController boardController;

    @Test
    void 일반_사용자는_공지사항을_작성할_수_없다() {
        BoardRequest request = request("공지사항");
        var authentication = authentication("ROLE_USER");

        assertThatThrownBy(() -> boardController.createBoard(request, authentication))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.NOTICE_ADMIN_ONLY);

        verifyNoInteractions(boardService);
    }

    @Test
    void 관리자는_공지사항을_작성할_수_있다() {
        BoardRequest request = request("공지사항");
        var authentication = authentication("ROLE_ADMIN");

        boardController.createBoard(request, authentication);

        verify(boardService).createBoard(request);
    }

    @Test
    void 일반_사용자는_자유게시판_글을_작성할_수_있다() {
        BoardRequest request = request("자유게시판");
        var authentication = authentication("ROLE_USER");

        boardController.createBoard(request, authentication);

        verify(boardService).createBoard(request);
    }

    private BoardRequest request(String category) {
        BoardRequest request = new BoardRequest();
        request.setTitle("제목");
        request.setContent("내용");
        request.setWriter("tester");
        request.setCategory(category);
        return request;
    }

    private UsernamePasswordAuthenticationToken authentication(String authority) {
        return new UsernamePasswordAuthenticationToken(
                1L,
                null,
                List.of(new SimpleGrantedAuthority(authority))
        );
    }
}
