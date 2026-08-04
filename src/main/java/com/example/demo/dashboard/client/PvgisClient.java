package com.example.demo.dashboard.client;


import tools.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class PvgisClient {

    public static final String SOURCE = "PVGIS 5.3";
    public static final String METHOD = "LOCATION_BASED_PV_SIMULATION";

    private final RestClient restClient;
    private final boolean enabled;
    private final double systemLossPercent;

    public PvgisClient(
            RestClient.Builder restClientBuilder,
            @Value("${pvgis.base-url}") String baseUrl,
            @Value("${pvgis.enabled:true}") boolean enabled,
            @Value("${pvgis.system-loss-percent:14}") double systemLossPercent,
            @Value("${pvgis.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${pvgis.read-timeout-ms:7000}") int readTimeoutMs
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
        this.enabled = enabled;
        this.systemLossPercent = systemLossPercent;
    }

    public Optional<Forecast> forecast(Request request) {
        if (!enabled || request == null || !request.isValid()) {
            return Optional.empty();
        }

        try {
            JsonNode response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/PVcalc")
                            .queryParam("lat", request.latitude())
                            .queryParam("lon", request.longitude())
                            .queryParam("peakpower", request.capacityKw())
                            .queryParam("loss", systemLossPercent)
                            .queryParam("angle", request.tiltDegrees())
                            .queryParam("aspect", request.azimuthDegrees())
                            .queryParam("mountingplace", request.mountingPlace())
                            .queryParam("pvtechchoice", "crystSi")
                            .queryParam("outputformat", "json")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            return parse(response, request, systemLossPercent);
        } catch (RestClientException exception) {
            log.warn(
                    "PVGIS 발전량 조회 실패 lat={}, lon={}, capacity={}kW: {}",
                    request.latitude(),
                    request.longitude(),
                    request.capacityKw(),
                    exception.getMessage()
            );
            return Optional.empty();
        }
    }

    static Optional<Forecast> parse(JsonNode response, Request request, double systemLossPercent) {
        JsonNode fixedMonthly = response == null
                ? null
                : response.path("outputs").path("monthly").path("fixed");
        if (fixedMonthly == null || !fixedMonthly.isArray()) {
            return Optional.empty();
        }

        List<MonthlyGeneration> monthly = new ArrayList<>();
        for (JsonNode item : fixedMonthly) {
            int month = item.path("month").asInt(0);
            double generationKwh = item.path("E_m").asDouble(Double.NaN);
            if (month >= 1 && month <= 12 && Double.isFinite(generationKwh) && generationKwh >= 0) {
                monthly.add(new MonthlyGeneration(month, Math.round(generationKwh)));
            }
        }
        monthly.sort(Comparator.comparingInt(MonthlyGeneration::month));
        if (monthly.size() != 12) {
            return Optional.empty();
        }

        long annualGenerationKwh = monthly.stream()
                .mapToLong(MonthlyGeneration::generationKwh)
                .sum();
        String radiationDatabase = response.path("inputs")
                .path("meteo_data")
                .path("radiation_db")
                .asText("")
                .replace("PVGIS-", "");
        String source = radiationDatabase.isBlank()
                ? SOURCE
                : SOURCE + " / " + radiationDatabase;
        return Optional.of(new Forecast(
                source,
                METHOD,
                request.capacityKw(),
                request.tiltDegrees(),
                request.azimuthDegrees(),
                systemLossPercent,
                false,
                List.copyOf(monthly),
                annualGenerationKwh
        ));
    }

    public record Request(
            double latitude,
            double longitude,
            int capacityKw,
            double tiltDegrees,
            double azimuthDegrees,
            String mountingPlace
    ) {
        public boolean isValid() {
            return latitude >= -90 && latitude <= 90
                    && longitude >= -180 && longitude <= 180
                    && capacityKw > 0
                    && tiltDegrees >= 0 && tiltDegrees <= 90
                    && azimuthDegrees >= -180 && azimuthDegrees <= 180
                    && ("free".equals(mountingPlace) || "building".equals(mountingPlace));
        }
    }

    public record MonthlyGeneration(int month, long generationKwh) {}

    public record Forecast(
            String source,
            String method,
            int capacityKw,
            double tiltDegrees,
            double azimuthDegrees,
            double systemLossPercent,
            boolean fallback,
            List<MonthlyGeneration> monthly,
            long annualGenerationKwh
    ) {}
}
