package com.example.demo.idleland.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.idleland.dto.IdleLandImportResultDto;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// 관리자가 업로드하는 유휴부지 원본(Uninstalled) CSV로 idle_land 테이블을 전량 교체한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class IdleLandCsvImportService {
    @Transactional
    public IdleLandImportResultDto replaceAll(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.IDLE_LAND_CSV_PARSE_FAILED, "업로드된 파일이 없습니다.");
        }

        List<IdleLand> parsed = parse(file);
        if (parsed.isEmpty()) {
            throw new CustomException(ErrorCode.IDLE_LAND_CSV_PARSE_FAILED, "업로드한 CSV에 유효한 데이터가 없습니다.");
        }

        idleLandRepository.truncateTable();
        idleLandRepository.saveAll(parsed);

        int land = 0;
        int building = 0;
        int unknown = 0;
        for (IdleLand row : parsed) {
            switch (row.getAssetTypeNorm()) {
                case "LAND" -> land++;
                case "BUILDING" -> building++;
                default -> unknown++;
            }
        }

        log.info("유휴부지 CSV 업로드로 전체 교체 완료: 총 {}건 (토지 {}, 건물 {}, 미확인 {})",
                parsed.size(), land, building, unknown);

        return new IdleLandImportResultDto(parsed.size(), land, building, unknown);
    }


    private static final List<String> REQUIRED_COLUMNS = List.of(
            "source_id_ml", "address_ml", "longitude", "latitude", "시도", "시군구",
            "자산구분_ML", "설치구분", "label",
            "ghi_avg_daily", "pvout_avg_daily", "dni_avg_daily", "dif_avg_daily", "gti_avg_daily", "temp_avg",
            "wind_speed_10m", "wind_speed_50m", "wind_speed_100m", "slope_avg", "slope_dir", "elevation_avg",
            "Hillshade", "Southness", "distance_to_substation_km", "distance_to_powerline_km",
            "substation_count_5km", "powerline_length_5km_km", "high_voltage_line_nearby_5km",
            "substation_max_voltage_kv", "powerline_max_voltage_kv",
            "substation_max_voltage_kv_missing", "powerline_max_voltage_kv_missing",
            "asset_type_code", "region_group"
    );
    private static final Set<String> LAND_VALUES = Set.of("토지", "토지형", "LAND", "land", "0");

    private static final Set<String> BUILDING_VALUES = Set.of("건물", "건물형", "BUILDING", "building", "1");

    private final IdleLandRepository idleLandRepository;

    private List<IdleLand> parse(MultipartFile file) {
        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.IDLE_LAND_CSV_PARSE_FAILED);
        }
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }

        try (CSVParser parser = CSVParser.parse(content, CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build())) {

            List<String> headers = parser.getHeaderNames();
            List<String> missing = REQUIRED_COLUMNS.stream().filter(column -> !headers.contains(column)).toList();
            if (!missing.isEmpty()) {
                throw new CustomException(ErrorCode.IDLE_LAND_CSV_PARSE_FAILED, "CSV에 필수 컬럼이 없습니다: " + missing);
            }

            List<IdleLand> result = new ArrayList<>();
            for (CSVRecord record : parser) {
                result.add(toEntity(record));
            }
            return result;
        } catch (IOException e) {
            log.error("유휴부지 CSV 파싱 실패", e);
            throw new CustomException(ErrorCode.IDLE_LAND_CSV_PARSE_FAILED);
        }
    }

    private IdleLand toEntity(CSVRecord record) {
        String assetTypeRaw = str(record, "자산구분_ML");
        return IdleLand.builder()
                .sourceId(str(record, "source_id_ml"))
                .address(str(record, "address_ml"))
                .longitude(dbl(record, "longitude"))
                .latitude(dbl(record, "latitude"))
                .sido(str(record, "시도"))
                .sigungu(str(record, "시군구"))
                .assetTypeRaw(assetTypeRaw)
                .installationType(str(record, "설치구분"))
                .label(intg(record, "label"))
                .ghiAvgDaily(dbl(record, "ghi_avg_daily"))
                .pvoutAvgDaily(dbl(record, "pvout_avg_daily"))
                .dniAvgDaily(dbl(record, "dni_avg_daily"))
                .difAvgDaily(dbl(record, "dif_avg_daily"))
                .gtiAvgDaily(dbl(record, "gti_avg_daily"))
                .tempAvg(dbl(record, "temp_avg"))
                .windSpeed10m(dbl(record, "wind_speed_10m"))
                .windSpeed50m(dbl(record, "wind_speed_50m"))
                .windSpeed100m(dbl(record, "wind_speed_100m"))
                .slopeAvg(dbl(record, "slope_avg"))
                .slopeDir(dbl(record, "slope_dir"))
                .elevationAvg(dbl(record, "elevation_avg"))
                .hillshade(dbl(record, "Hillshade"))
                .southness(dbl(record, "Southness"))
                .distanceToSubstationKm(dbl(record, "distance_to_substation_km"))
                .distanceToPowerlineKm(dbl(record, "distance_to_powerline_km"))
                .substationCount5km(intg(record, "substation_count_5km"))
                .powerlineLength5kmKm(dbl(record, "powerline_length_5km_km"))
                .highVoltageLineNearby5km(intg(record, "high_voltage_line_nearby_5km"))
                .substationMaxVoltageKv(dbl(record, "substation_max_voltage_kv"))
                .powerlineMaxVoltageKv(dbl(record, "powerline_max_voltage_kv"))
                .substationMaxVoltageKvMissing(intg(record, "substation_max_voltage_kv_missing"))
                .powerlineMaxVoltageKvMissing(intg(record, "powerline_max_voltage_kv_missing"))
                .assetTypeCode(intg(record, "asset_type_code"))
                .regionGroup(str(record, "region_group"))
                .assetTypeNorm(normalizeAssetType(assetTypeRaw))
                .build();
    }

    private String normalizeAssetType(String value) {
        if (value == null) {
            return "UNKNOWN";
        }
        String trimmed = value.trim();
        if (LAND_VALUES.contains(trimmed)) {
            return "LAND";
        }
        if (BUILDING_VALUES.contains(trimmed)) {
            return "BUILDING";
        }
        return "UNKNOWN";
    }

    private String str(CSVRecord record, String column) {
        if (!record.isSet(column)) {
            return null;
        }
        String value = record.get(column);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private Double dbl(CSVRecord record, String column) {
        String value = str(record, column);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intg(CSVRecord record, String column) {
        String value = str(record, column);
        if (value == null) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
