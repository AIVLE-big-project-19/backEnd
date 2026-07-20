package com.example.demo.board.repository;

import com.example.demo.board.entity.Board;
import com.example.demo.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
@Import(JpaAuditingConfig.class)
class BoardRepositoryTest {

    @Autowired
    private BoardRepository boardRepository;

    private Board newBoard(String writer, String title) {
        return boardRepository.save(Board.builder()
                .title(title)
                .content("내용")
                .writer(writer)
                .category("자유게시판")
                .build());
    }

    @Test
    void writer_교체시_해당_작성자의_게시글만_바뀐다() {
        newBoard("tester01", "내 글 1");
        newBoard("tester01", "내 글 2");
        newBoard("other99", "남의 글");

        int updated = boardRepository.replaceWriter("tester01", "탈퇴한 사용자");

        assertThat(updated).isEqualTo(2);

        List<Board> all = boardRepository.findAll();
        assertThat(all).filteredOn(b -> b.getWriter().equals("탈퇴한 사용자")).hasSize(2);
        assertThat(all).filteredOn(b -> b.getWriter().equals("other99")).hasSize(1);
    }
}
