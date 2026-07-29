package com.example.demo.idleland.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.idleland.client.MlScoringClient;
import com.example.demo.idleland.dto.IdleLandSearchResultDto;
import com.example.demo.idleland.dto.MlRankResponse;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 주소 검색어로 유휴부지 후보를 DB에서 찾고, dataset_type(LAND/BUILDING)별로 묶어
// Ranking_ML을 호출해 점수·등급·순위를 붙여 반환한다.
@Service
@RequiredArgsConstructor
public class IdleLandSearchService {

    private final IdleLandRepository idleLandRepository;
    private final MlScoringClient mlScoringClient;

    public List<IdleLandSearchResultDto> findAllScored() {
        return scoreAndSort(idleLandRepository.findAll());
    }

    public Page<IdleLandSearchResultDto> findByRegionScored(
            String sido,
            String sigungu,
            int requestedPage,
            int requestedSize
    ) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(1_000, Math.max(1, requestedSize));
        List<IdleLand> matched = idleLandRepository.findAll(buildRegionSpecification(sido, sigungu));
        List<IdleLandSearchResultDto> scored = scoreAndSort(matched);
        int fromIndex = Math.min(page * size, scored.size());
        int toIndex = Math.min(fromIndex + size, scored.size());

        return new PageImpl<>(scored.subList(fromIndex, toIndex), PageRequest.of(page, size), scored.size());
    }

    public List<IdleLandSearchResultDto> search(String query) {
        List<String> tokens = query == null
                ? List.of()
                : List.of(query.trim().split("\\s+")).stream().filter(t -> !t.isBlank()).toList();

        if (tokens.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "검색어를 입력해주세요.");
        }

        List<IdleLand> matched = idleLandRepository.findAll(buildAddressSpecification(tokens));
        if (matched.isEmpty()) {
            return List.of();
        }

        return scoreAndSort(matched);
    }

    private List<IdleLandSearchResultDto> scoreAndSort(List<IdleLand> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Map<String, List<IdleLand>> byType = candidates.stream()
                .filter(row -> "LAND".equals(row.getAssetTypeNorm()) || "BUILDING".equals(row.getAssetTypeNorm()))
                .collect(Collectors.groupingBy(IdleLand::getAssetTypeNorm));

        List<IdleLandSearchResultDto> results = new ArrayList<>();
        for (Map.Entry<String, List<IdleLand>> entry : byType.entrySet()) {
            String datasetType = entry.getKey().equalsIgnoreCase("BUILDING") ? "building" : "land";
            results.addAll(scoreGroup(datasetType, entry.getKey(), entry.getValue()));
        }

        results.sort(Comparator.comparing(
                IdleLandSearchResultDto::getSolarReadinessScore,
                Comparator.nullsLast(Comparator.reverseOrder())
        ));

        return results;
    }

    private List<IdleLandSearchResultDto> scoreGroup(String datasetType, String assetType, List<IdleLand> rows) {
        MlRankResponse response = mlScoringClient.rank(datasetType, rows, 0, false);

        Map<String, Map<String, Object>> bySourceId = response.getRanking() == null
                ? Map.of()
                : response.getRanking().stream()
                        .collect(Collectors.toMap(
                                row -> String.valueOf(row.get("source_id_ml")),
                                row -> row,
                                (a, b) -> a));

        List<IdleLandSearchResultDto> results = new ArrayList<>();
        for (IdleLand row : rows) {
            Map<String, Object> scoreRow = bySourceId.get(row.mlSourceId());
            results.add(new IdleLandSearchResultDto(
                    row.getId(),
                    row.getSourceId(),
                    row.getAddress(),
                    row.getLongitude(),
                    row.getLatitude(),
                    row.getSido(),
                    row.getSigungu(),
                    assetType,
                    scoreRow == null ? null : toDouble(scoreRow.get("Solar_Readiness_Score")),
                    scoreRow == null ? null : String.valueOf(scoreRow.get("Solar_Readiness_Grade")),
                    scoreRow == null ? null : toInteger(scoreRow.get("Candidate_Rank"))
            ));
        }
        return results;
    }

    private Specification<IdleLand> buildAddressSpecification(List<String> tokens) {
        return (root, cq, cb) -> {
            Predicate predicate = cb.conjunction();
            for (String token : tokens) {
                predicate = cb.and(predicate, cb.like(cb.lower(root.get("address")), "%" + token.toLowerCase() + "%"));
            }
            return predicate;
        };
    }

    private Specification<IdleLand> buildRegionSpecification(String sido, String sigungu) {
        return (root, cq, cb) -> {
            Predicate predicate = cb.conjunction();
            if (sido != null && !sido.isBlank()) {
                predicate = cb.and(predicate, cb.or(
                        cb.equal(root.get("sido"), sido),
                        cb.like(root.get("address"), "%" + sido + "%")
                ));
            }
            if (sigungu != null && !sigungu.isBlank()) {
                predicate = cb.and(predicate, cb.or(
                        cb.equal(root.get("sigungu"), sigungu),
                        cb.like(root.get("address"), "%" + sigungu + "%")
                ));
            }
            return predicate;
        };
    }

    private Double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Integer toInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
