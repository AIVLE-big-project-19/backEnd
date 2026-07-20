package com.example.demo.chat.service;

import com.example.demo.chat.dto.ChatActionDto;
import com.example.demo.chat.dto.ChatRequest;
import com.example.demo.chat.dto.ChatResponse;
import com.example.demo.chat.entity.ChatMessage;
import com.example.demo.chat.entity.ChatRole;
import com.example.demo.chat.repository.ChatMessageRepository;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.openai.client.OpenAIClient;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final String SYSTEM_PROMPT =
            "당신은 SolarAivle 서비스의 챗봇입니다. 태양광 발전 설치, 지도 검색, 보고서 다운로드 등 서비스 이용을 친절하고 간결하게 안내하세요. "
                    + "사용자가 로그인, 회원가입, 게시판, 게시글 작성, 메인(지도) 화면 등 특정 페이지로 이동하고 싶다고 말하면 "
                    + "navigate_to_page 도구를 호출해서 이동을 도와주세요.";

    private static final Map<String, String> PAGE_PATHS = Map.of(
            "HOME", "/",
            "LOGIN", "/login",
            "SIGNUP", "/signup",
            "BOARDS", "/boards",
            "BOARD_WRITE", "/boards/write"
    );

    private static final Map<String, String> PAGE_REPLIES = Map.of(
            "HOME", "메인 화면으로 이동할게요!",
            "LOGIN", "로그인 페이지로 이동할게요!",
            "SIGNUP", "회원가입 페이지로 이동할게요!",
            "BOARDS", "게시판으로 이동할게요!",
            "BOARD_WRITE", "게시글 작성 페이지로 이동할게요!" //우선 테스트 용으로 구현된 사이트만 이동 추후 기능 전면 수정
    );

    private final OpenAIClient openAIClient;
    private final ChatMessageRepository chatMessageRepository;


    static class NavigateToPage {

        @JsonPropertyDescription(
                "이동할 페이지. 반드시 HOME(메인/지도 화면), LOGIN(로그인), SIGNUP(회원가입), "
                        + "BOARDS(게시판 목록), BOARD_WRITE(게시글 작성) 중 하나여야 합니다."
        )
        public String page;

    }

    @Override
    public ChatResponse sendMessage(Long userId, ChatRequest request) {

        List<ChatMessage> history = userId != null
                ? chatMessageRepository.findTop20ByUserIdOrderByChatMessageIdDesc(userId)
                : Collections.emptyList();

        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                .model(ChatModel.GPT_4O_MINI)
                .maxCompletionTokens(1024L)
                .addSystemMessage(SYSTEM_PROMPT)
                .addTool(NavigateToPage.class);

        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage past = history.get(i);
            if (past.getRole() == ChatRole.USER) {
                paramsBuilder.addUserMessage(past.getContent());
            } else {
                paramsBuilder.addAssistantMessage(past.getContent());
            }
        }
        paramsBuilder.addUserMessage(request.getMessage());

        String reply;
        ChatActionDto action = null;

        try {
            ChatCompletion completion = openAIClient.chat().completions().create(paramsBuilder.build());
            ChatCompletionMessage message = completion.choices().get(0).message();

            Optional<ChatCompletionMessageToolCall> navigateCall = message.toolCalls()
                    .orElse(Collections.emptyList())
                    .stream()
                    .filter(ChatCompletionMessageToolCall::isFunction)
                    .findFirst();

            if (navigateCall.isPresent()) {
                NavigateToPage args = navigateCall.get().asFunction().function().arguments(NavigateToPage.class);
                String page = args.page != null ? args.page.trim().toUpperCase() : "";

                reply = PAGE_REPLIES.getOrDefault(page, "이동을 도와드릴게요!");
                action = ChatActionDto.builder()
                        .type("NAVIGATE")
                        .path(PAGE_PATHS.getOrDefault(page, "/"))
                        .build();
            } else {
                reply = message.content().orElse("죄송해요, 답변을 가져오지 못했어요.");
            }
        } catch (OpenAIServiceException e) {
            log.error("OpenAI 챗봇 응답 생성 실패", e);
            throw new CustomException(ErrorCode.CHAT_REQUEST_FAILED);
        }

        chatMessageRepository.save(ChatMessage.builder()
                .userId(userId)
                .role(ChatRole.USER)
                .content(request.getMessage())
                .build());

        chatMessageRepository.save(ChatMessage.builder()
                .userId(userId)
                .role(ChatRole.ASSISTANT)
                .content(reply)
                .build());

        return ChatResponse.builder()
                .reply(reply)
                .action(action)
                .build();
    }

    private record CandidateSite(String name, String address, String spaceType, String area, String note, int score) {}

    @Override
    public ChatResponse analyzeExcel(Long userId, MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.EXCEL_PARSE_FAILED);
        }

        List<CandidateSite> candidates = new ArrayList<>();
        Random random = new Random();

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int rowIdx = 1; rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                Row row = sheet.getRow(rowIdx);
                if (row == null) {
                    continue;
                }

                String name = getCellString(row.getCell(1));
                if (name.isBlank()) {
                    continue;
                }

                String address = getCellString(row.getCell(2));
                String spaceType = getCellString(row.getCell(3));
                String area = getCellString(row.getCell(4));
                String note = getCellString(row.getCell(5));

                // AI 분석 로직 미구현으로 임시 난수 점수 부여
                int score = random.nextInt(101);

                candidates.add(new CandidateSite(name, address, spaceType, area, note, score));
            }
        } catch (IOException e) {
            log.error("엑셀 파일 파싱 실패", e);
            throw new CustomException(ErrorCode.EXCEL_PARSE_FAILED);
        }

        if (candidates.isEmpty()) {
            throw new CustomException(ErrorCode.EXCEL_PARSE_FAILED);
        }

        CandidateSite best = candidates.stream()
                .max(Comparator.comparingInt(CandidateSite::score))
                .orElseThrow();

        StringBuilder sb = new StringBuilder();
        sb.append("업로드하신 후보지 ").append(candidates.size()).append("곳을 분석했어요.\n\n");
        for (CandidateSite c : candidates) {
            sb.append(String.format("- %s (%s, %s㎡): %d점%n", c.name(), c.spaceType(), c.area(), c.score()));
        }
        sb.append(String.format("%n가장 적합한 곳은 [%s] 입니다! (%d점)%n", best.name(), best.score()));
        sb.append("주소: ").append(best.address());

        String reply = sb.toString();

        chatMessageRepository.save(ChatMessage.builder()
                .userId(userId)
                .role(ChatRole.USER)
                .content("[엑셀 업로드] 후보지 " + candidates.size() + "곳 분석 요청")
                .build());

        chatMessageRepository.save(ChatMessage.builder()
                .userId(userId)
                .role(ChatRole.ASSISTANT)
                .content(reply)
                .build());

        return ChatResponse.builder()
                .reply(reply)
                .build();
    }

    private String getCellString(Cell cell) {
        if (cell == null) {
            return "";
        }

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                double value = cell.getNumericCellValue();
                yield value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }

}
