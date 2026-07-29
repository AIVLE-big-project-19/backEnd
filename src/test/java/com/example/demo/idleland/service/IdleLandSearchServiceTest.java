package com.example.demo.idleland.service;

import com.example.demo.idleland.client.MlScoringClient;
import com.example.demo.idleland.dto.MlRankResponse;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class IdleLandSearchServiceTest {

    @Mock
    private IdleLandRepository idleLandRepository;

    @Mock
    private MlScoringClient mlScoringClient;

    @InjectMocks
    private IdleLandSearchService service;

    @Test
    void 업로드된_후보지_전체를_유형별로_채점해_반환한다() {
        IdleLand land = candidate(1L, "LAND-1", "LAND");
        IdleLand building = candidate(2L, "BUILDING-1", "BUILDING");
        when(idleLandRepository.findAll()).thenReturn(List.of(land, building));
        when(mlScoringClient.rank("land", List.of(land), 0, false))
                .thenReturn(rank("LAND-1", 88.5, "A", 2));
        when(mlScoringClient.rank("building", List.of(building), 0, false))
                .thenReturn(rank("BUILDING-1", 92.1, "A", 1));

        var result = service.findAllScored();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getSourceId()).isEqualTo("BUILDING-1");
        assertThat(result.get(0).getSolarReadinessScore()).isEqualTo(92.1);
        verify(mlScoringClient).rank("land", List.of(land), 0, false);
        verify(mlScoringClient).rank("building", List.of(building), 0, false);
    }

    @Test
    void 시도와_시군구로_필터링한_후보지를_페이지로_반환한다() {
        IdleLand first = candidate(1L, "LAND-1", "LAND");
        IdleLand second = candidate(2L, "LAND-2", "LAND");
        when(idleLandRepository.findAll(any(Specification.class))).thenReturn(List.of(first, second));
        when(mlScoringClient.rank("land", List.of(first, second), 0, false))
                .thenReturn(rankRows(
                        Map.of("source_id_ml", "LAND-1", "Solar_Readiness_Score", 95d, "Solar_Readiness_Grade", "A", "Candidate_Rank", 1),
                        Map.of("source_id_ml", "LAND-2", "Solar_Readiness_Score", 85d, "Solar_Readiness_Grade", "A", "Candidate_Rank", 2)
                ));

        var result = service.findByRegionScored("충청남도", "홍성군", 0, 1);

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getSourceId()).isEqualTo("LAND-1");
    }

    private IdleLand candidate(Long id, String sourceId, String assetType) {
        return IdleLand.builder()
                .id(id)
                .sourceId(sourceId)
                .address("충청남도 테스트 주소 " + id)
                .assetTypeNorm(assetType)
                .build();
    }

    private MlRankResponse rank(String sourceId, double score, String grade, int rank) {
        return rankRows(Map.of(
                "source_id_ml", sourceId,
                "Solar_Readiness_Score", score,
                "Solar_Readiness_Grade", grade,
                "Candidate_Rank", rank
        ));
    }

    @SafeVarargs
    private MlRankResponse rankRows(Map<String, Object>... rows) {
        MlRankResponse response = new MlRankResponse();
        response.setRanking(List.of(rows));
        return response;
    }
}
