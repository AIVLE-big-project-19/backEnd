package com.example.demo.idleland.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.idleland.dto.IdleLandParcelDataDto;
import com.example.demo.idleland.dto.IdleLandSearchResultDto;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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


@Service
@RequiredArgsConstructor
public class IdleLandSearchService {

    private final IdleLandRepository idleLandRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IdleLandParcelDataDto parcelData(Long id) {
        IdleLand idleLand = idleLandRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.IDLE_LAND_NOT_FOUND));
        return new IdleLandParcelDataDto(
                parseJson(idleLand.getParcelGeometryJson()),
                parseJson(idleLand.getPanelLayoutJson())
        );
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
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

        List<IdleLand> sorted = candidates.stream()
                .filter(row -> "LAND".equals(row.getAssetTypeNorm()) || "BUILDING".equals(row.getAssetTypeNorm()))
                .sorted(Comparator.comparing(IdleLand::getSolarReadinessScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<IdleLandSearchResultDto> results = new ArrayList<>();
        int rank = 0;
        for (IdleLand row : sorted) {
            Integer searchRank = row.getSolarReadinessScore() == null ? null : ++rank;
            results.add(new IdleLandSearchResultDto(
                    row.getId(),
                    row.getSourceId(),
                    row.getAddress(),
                    row.getLongitude(),
                    row.getLatitude(),
                    row.getSido(),
                    row.getSigungu(),
                    row.getAssetTypeNorm(),
                    row.getSolarReadinessScore(),
                    row.getSolarReadinessGrade(),
                    searchRank
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
}
