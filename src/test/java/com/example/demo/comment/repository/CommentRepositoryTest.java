package com.example.demo.comment.repository;

import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardRepository;
import com.example.demo.comment.entity.Comment;
import com.example.demo.global.config.JpaAuditingConfig;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
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
class CommentRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Test
    void 익명화하면_해당_작성자의_댓글만_FK가_끊기고_표시명이_교체된다() {
        User author = userRepository.save(User.builder()
                .loginId("tester01")
                .email("withdraw-comment@test.com")
                .name("테스터")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());

        Board board = boardRepository.save(Board.builder()
                .title("제목")
                .content("내용")
                .writer("tester01")
                .category("자유게시판")
                .build());

        commentRepository.save(Comment.builder()
                .board(board).writer("tester01").author(author).content("내 댓글 1").build());
        commentRepository.save(Comment.builder()
                .board(board).writer("tester01").author(author).content("내 댓글 2").build());
        commentRepository.save(Comment.builder()
                .board(board).writer("other99").content("남의 댓글").build());

        int updated = commentRepository.anonymizeByAuthor(author, "탈퇴한 사용자");

        assertThat(updated).isEqualTo(2);

        List<Comment> all = commentRepository.findAll();
        assertThat(all).filteredOn(c -> c.getWriter().equals("탈퇴한 사용자"))
                .hasSize(2)
                .allSatisfy(c -> assertThat(c.getAuthor()).isNull());
        assertThat(all).filteredOn(c -> c.getWriter().equals("other99")).hasSize(1);
    }
}
