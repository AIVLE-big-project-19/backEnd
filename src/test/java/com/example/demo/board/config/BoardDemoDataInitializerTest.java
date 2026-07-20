package com.example.demo.board.config;

import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BoardDemoDataInitializerTest {

    @Mock
    private BoardRepository boardRepository;

    @Captor
    private ArgumentCaptor<List<Board>> boardsCaptor;

    private BoardDemoDataInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new BoardDemoDataInitializer(boardRepository);
    }

    @Test
    void 공지사항과_FAQ를_각각_5개씩_생성한다() {
        when(boardRepository.existsByCategoryAndTitle(anyString(), anyString())).thenReturn(false);
        initializer.run(null);

        verify(boardRepository).saveAll(boardsCaptor.capture());
        List<Board> saved = boardsCaptor.getValue();
        assertThat(saved).hasSize(10);
        assertThat(saved).filteredOn(board -> "공지사항".equals(board.getCategory())).hasSize(5);
        assertThat(saved).filteredOn(board -> "FAQ".equals(board.getCategory())).hasSize(5);
        assertThat(saved).allMatch(board -> "SolarAivle 운영팀".equals(board.getWriter()));
    }

    @Test
    void 이미_등록된_데모글은_중복_생성하지_않는다() {
        when(boardRepository.existsByCategoryAndTitle(anyString(), anyString())).thenReturn(true);

        initializer.run(null);

        verify(boardRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }
}
