package com.example.demo.dashboard.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PvgisClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void PVGIS_JSON에서_12개월_발전량을_변환한다() throws Exception {
        String items = IntStream.rangeClosed(1, 12)
                .mapToObj(month -> "{\"month\":" + month + ",\"E_m\":" + (month * 1000.4) + "}")
                .collect(Collectors.joining(","));
        JsonNode response = objectMapper.readTree(
                "{\"inputs\":{\"meteo_data\":{\"radiation_db\":\"PVGIS-ERA5\"}},"
                        + "\"outputs\":{\"monthly\":{\"fixed\":[" + items + "]}}}"
        );
        PvgisClient.Request request = new PvgisClient.Request(36.6, 126.6, 100, 30, 0, "free");

        Optional<PvgisClient.Forecast> result = PvgisClient.parse(response, request, 14d);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().monthly()).hasSize(12);
        assertThat(result.orElseThrow().source()).isEqualTo("PVGIS 5.3 / ERA5");
        assertThat(result.orElseThrow().monthly().get(0).generationKwh()).isEqualTo(1_000L);
        assertThat(result.orElseThrow().monthly().get(11).generationKwh()).isEqualTo(12_005L);
        assertThat(result.orElseThrow().annualGenerationKwh()).isEqualTo(78_031L);
    }

    @Test
    void 월별_데이터가_12개월이_아니면_사용하지_않는다() throws Exception {
        JsonNode response = objectMapper.readTree("{\"outputs\":{\"monthly\":{\"fixed\":[{\"month\":1,\"E_m\":1000}]}}}");
        PvgisClient.Request request = new PvgisClient.Request(36.6, 126.6, 100, 30, 0, "free");

        assertThat(PvgisClient.parse(response, request, 14d)).isEmpty();
    }
}
