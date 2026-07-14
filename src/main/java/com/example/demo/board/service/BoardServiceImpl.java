package com.example.demo.board.service;

import com.example.demo.board.dto.BoardRequest;
import com.example.demo.board.dto.BoardResponse;
import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardRepository;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.global.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Pageable;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BoardServiceImpl implements BoardService {

    private final BoardRepository boardRepository;

    @Override
    public BoardResponse createBoard(BoardRequest request) {

        Board board = Board.builder()
                .title(request.getTitle())
                .content(request.getContent())
                .writer(request.getWriter())
                .category(request.getCategory())
                .build();

        Board savedBoard = boardRepository.save(board);

        return entityToResponse(savedBoard);
    }

    @Override
    public BoardResponse getBoard(Long boardId) {

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.BOARD_NOT_FOUND));

        board.increaseViewCount();
        boardRepository.save(board);

        return entityToResponse(board);
    }

    @Override
    public PageResponse<BoardResponse> getBoards(Pageable pageable){

        return PageResponse.from(

                boardRepository.findAll(pageable)
                        .map(this::entityToResponse)
        );
    }

    @Override
    public BoardResponse updateBoard(Long boardId, BoardRequest request) {

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.BOARD_NOT_FOUND));

        board.update(
                request.getTitle(),
                request.getContent(),
                request.getCategory()
        );

        Board updatedBoard = boardRepository.save(board);

        return entityToResponse(updatedBoard);
    }

    @Override
    public void deleteBoard(Long boardId) {

        Board board = boardRepository.findById(boardId)
                .orElseThrow(() ->
                        new CustomException(ErrorCode.BOARD_NOT_FOUND));

        boardRepository.delete(board);

    }

    /**
     * Entity → DTO 변환
     */
    private BoardResponse entityToResponse(Board board){

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