package com.example.demo.board.controller;

import com.example.demo.board.dto.BoardRequest;
import com.example.demo.board.service.BoardService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BoardControllerTest {

    @Mock
    private BoardService boardService;

    @InjectMocks
    private BoardController boardController;

    @Test
    void 일반_사용자의_권한정보를_서비스에_전달한다() {
        BoardRequest request = request("공지사항");
        var authentication = authentication("ROLE_USER");

        boardController.createBoard(request, authentication);

        verify(boardService).createBoard(request, 1L, false);
    }

    @Test
    void 관리자는_공지사항을_작성할_수_있다() {
        BoardRequest request = request("공지사항");
        var authentication = authentication("ROLE_ADMIN");

        boardController.createBoard(request, authentication);

        verify(boardService).createBoard(request, 1L, true);
    }

    @Test
    void 일반_사용자는_자유게시판_글을_작성할_수_있다() {
        BoardRequest request = request("자유게시판");
        var authentication = authentication("ROLE_USER");

        boardController.createBoard(request, authentication);

        verify(boardService).createBoard(request, 1L, false);
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
