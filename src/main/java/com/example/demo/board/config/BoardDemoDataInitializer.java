package com.example.demo.board.config;

import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.demo-data.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class BoardDemoDataInitializer implements ApplicationRunner {

    private static final String WRITER = "SolarAivle 운영팀";

    private static final List<DemoBoard> DEMO_BOARDS = List.of(
            new DemoBoard(
                    "공지사항",
                    "SolarAivle 서비스 이용 안내",
                    "SolarAivle은 태양광 발전 후보지를 검색하고 분석 결과를 확인할 수 있는 서비스입니다.\n\n"
                            + "지도에서 원하는 지역을 검색한 뒤 후보지를 선택하면 주소 정보를 확인하고 부지 분석 보고서를 내려받을 수 있습니다. "
                            + "서비스 이용 중 도움이 필요하면 FAQ 또는 1:1 문의를 이용해 주세요."
            ),
            new DemoBoard(
                    "공지사항",
                    "태양광 후보지 검색 기능 안내",
                    "홈 화면의 검색창에서 주소나 장소명을 입력하면 관련 위치를 확인할 수 있습니다.\n\n"
                            + "검색 결과를 선택하면 지도가 해당 위치로 이동하며, 지도를 직접 움직여 주변 지역을 추가로 살펴볼 수도 있습니다. "
                            + "정확한 분석을 위해 도로명 주소와 실제 설치 대상 위치를 함께 확인해 주세요."
            ),
            new DemoBoard(
                    "공지사항",
                    "부지 분석 보고서 다운로드 안내",
                    "지도에서 유효한 주소를 선택한 후 '보고서 다운로드' 버튼을 누르면 PDF 형식의 부지 분석 보고서를 받을 수 있습니다.\n\n"
                            + "보고서는 후보지 검토를 위한 참고 자료이며, 실제 설치 전에는 현장 조사와 관련 법규 및 계통 연계 조건을 별도로 확인해야 합니다."
            ),
            new DemoBoard(
                    "공지사항",
                    "서비스 점검 및 데이터 갱신 안내",
                    "안정적인 서비스 제공과 지도·분석 데이터 갱신을 위해 비정기 점검이 진행될 수 있습니다.\n\n"
                            + "점검 중에는 지도 검색, 보고서 생성 또는 AI 분석 응답이 일시적으로 지연될 수 있습니다. "
                            + "주요 점검 일정은 공지사항을 통해 사전에 안내드리겠습니다."
            ),
            new DemoBoard(
                    "공지사항",
                    "개인정보 보호 및 계정 보안 안내",
                    "비밀번호와 이메일 인증번호를 다른 사람에게 공유하지 마세요. 공용 기기에서는 이용 후 반드시 로그아웃해 주세요.\n\n"
                            + "비정상적인 로그인이나 계정 정보 변경이 의심되면 비밀번호를 재설정하고 1:1 문의를 통해 운영팀에 알려주시기 바랍니다."
            ),
            new DemoBoard(
                    "FAQ",
                    "태양광 설치 후보지는 어떻게 검색하나요?",
                    "홈 화면 검색창에 도로명 주소, 지번 주소 또는 장소명을 입력하세요.\n\n"
                            + "검색 결과에서 원하는 항목을 선택하면 지도가 해당 위치로 이동합니다. 지도를 확대하거나 이동한 뒤 표시되는 주소를 확인하여 분석 대상지를 선택할 수 있습니다."
            ),
            new DemoBoard(
                    "FAQ",
                    "부지 분석 보고서는 어떻게 받을 수 있나요?",
                    "지도에서 분석할 위치를 선택해 유효한 주소가 표시되면 '보고서 다운로드' 버튼을 누르세요.\n\n"
                            + "보고서는 PDF 파일로 생성됩니다. 주소 정보가 없거나 통신 오류 메시지가 표시된 상태에서는 보고서를 생성할 수 없습니다."
            ),
            new DemoBoard(
                    "FAQ",
                    "엑셀 후보지 분석은 어떻게 이용하나요?",
                    "챗봇을 열고 '엑셀' 버튼을 눌러 후보지 정보가 포함된 엑셀 파일을 업로드하세요.\n\n"
                            + "파일 형식과 필수 항목이 올바르면 여러 후보지에 대한 분석 결과를 챗봇에서 확인할 수 있습니다. 오류가 발생하면 열 이름과 데이터 형식을 다시 확인해 주세요."
            ),
            new DemoBoard(
                    "FAQ",
                    "Google 계정으로 로그인할 수 있나요?",
                    "네. 로그인 화면에서 Google 로그인을 선택하면 별도의 SolarAivle 비밀번호 없이 서비스를 이용할 수 있습니다.\n\n"
                            + "Google 계정으로 작성한 게시글과 댓글에는 Google 프로필의 이름이 작성자로 표시됩니다."
            ),
            new DemoBoard(
                    "FAQ",
                    "1:1 문의 답변은 어디서 확인하나요?",
                    "게시판의 1:1 문의 카테고리에서 본인이 작성한 문의글을 선택하면 관리자 답변을 확인할 수 있습니다.\n\n"
                            + "문의 목록은 다른 회원에게도 표시될 수 있지만 상세 내용은 작성자와 관리자만 열람할 수 있습니다."
            )
    );

    private final BoardRepository boardRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Board> missingBoards = DEMO_BOARDS.stream()
                .filter(demo -> !boardRepository.existsByCategoryAndTitle(demo.category(), demo.title()))
                .map(this::toEntity)
                .toList();

        if (!missingBoards.isEmpty()) {
            boardRepository.saveAll(missingBoards);
        }
    }

    private Board toEntity(DemoBoard demo) {
        return Board.builder()
                .title(demo.title())
                .content(demo.content())
                .writer(WRITER)
                .category(demo.category())
                .build();
    }

    private record DemoBoard(String category, String title, String content) {
    }
}
