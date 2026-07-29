package com.example.demo.idleland.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.idleland.dto.MlRankResponse;
import com.example.demo.idleland.entity.IdleLand;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

// IdleLand 후보지 목록을 CSV로 만들어 Ranking_ML(FastAPI) POST /rank/{dataset_type}로 넘기고,
// 예측·랭킹·SHAP 결과를 받아온다.
@Slf4j
@Component
public class MlScoringClient {

    private static final List<String> CSV_COLUMNS = List.of(
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

    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;

    public MlScoringClient(@Value("${ml.server.base-url}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public MlRankResponse rank(String datasetType, List<IdleLand> rows, int topNJson, boolean includeShap) {
        if (rows == null || rows.isEmpty()) {
            throw new CustomException(ErrorCode.ML_SERVER_REQUEST_FAILED, "ML 서버에 보낼 후보지가 없습니다.");
        }

        byte[] csvBytes = toCsv(rows);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csvBytes) {
            @Override
            public String getFilename() {
                return "idle_lands.csv";
            }
        });
        body.add("top_n_json", String.valueOf(topNJson));
        body.add("include_shap", String.valueOf(includeShap));

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
        String url = baseUrl + "/rank/" + datasetType;

        try {
            ResponseEntity<MlRankResponse> response = restTemplate.postForEntity(url, requestEntity, MlRankResponse.class);
            MlRankResponse result = response.getBody();
            if (result == null) {
                throw new CustomException(ErrorCode.ML_SERVER_REQUEST_FAILED);
            }
            return result;
        } catch (RestClientException e) {
            log.error("ML 서버 호출 실패: {}", url, e);
            throw new CustomException(ErrorCode.ML_SERVER_REQUEST_FAILED);
        }
    }

    private byte[] toCsv(List<IdleLand> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder().setHeader(CSV_COLUMNS.toArray(new String[0])).build())) {

            for (IdleLand row : rows) {
                printer.printRecord(
                        row.mlSourceId(), row.getAddress(), row.getLongitude(), row.getLatitude(),
                        row.getSido(), row.getSigungu(), row.getAssetTypeRaw(), row.getInstallationType(), row.getLabel(),
                        row.getGhiAvgDaily(), row.getPvoutAvgDaily(), row.getDniAvgDaily(), row.getDifAvgDaily(),
                        row.getGtiAvgDaily(), row.getTempAvg(),
                        row.getWindSpeed10m(), row.getWindSpeed50m(), row.getWindSpeed100m(),
                        row.getSlopeAvg(), row.getSlopeDir(), row.getElevationAvg(),
                        row.getHillshade(), row.getSouthness(),
                        row.getDistanceToSubstationKm(), row.getDistanceToPowerlineKm(),
                        row.getSubstationCount5km(), row.getPowerlineLength5kmKm(), row.getHighVoltageLineNearby5km(),
                        row.getSubstationMaxVoltageKv(), row.getPowerlineMaxVoltageKv(),
                        row.getSubstationMaxVoltageKvMissing(), row.getPowerlineMaxVoltageKvMissing(),
                        row.getAssetTypeCode(), row.getRegionGroup()
                );
            }
        } catch (IOException e) {
            throw new CustomException(ErrorCode.ML_SERVER_REQUEST_FAILED, "ML 요청용 CSV 생성에 실패했습니다.");
        }
        return out.toByteArray();
    }
}
