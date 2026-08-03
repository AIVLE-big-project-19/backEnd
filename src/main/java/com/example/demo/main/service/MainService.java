package com.example.demo.main.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class MainService {
    @Value("${VWORLD_API_KEY}")
    private String vWorldApiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String searchVWorldPlace(String keyword) {
        RestTemplate restTemplate = new RestTemplate();

        try {
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String urlStr = "https://api.vworld.kr/req/search?service=search&request=search&version=2.0"
                    + "&crs=EPSG:900913"
                    + "&size=10&page=1"
                    + "&query=" + encodedKeyword
                    + "&type=place"
                    + "&format=json&errorformat=json"
                    + "&key=" + vWorldApiKey;

            URI uri = URI.create(urlStr);
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            return "{\"response\":{\"status\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}}";
        }
    }




    public String getVWorldAddress(String point) {
        String roadResult = callVWorldAddress(point, "road");
        if (isOk(roadResult)) {
            return roadResult;
        }
        return callVWorldAddress(point, "parcel");
    }

    private String callVWorldAddress(String point, String type) {
        RestTemplate restTemplate = new RestTemplate();

        try {
            String urlStr = "https://api.vworld.kr/req/address?service=address&request=getAddress"
                    + "&point=" + point
                    + "&type=" + type
                    + "&format=json&errorformat=json"
                    + "&key=" + vWorldApiKey;

            URI uri = URI.create(urlStr);
            return restTemplate.getForObject(uri, String.class);
        } catch (Exception e) {
            return "{\"response\":{\"status\":\"ERROR\",\"message\":\"" + e.getMessage() + "\"}}";
        }
    }

    private boolean isOk(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            return "OK".equals(root.path("response").path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    public String getVWorldApiKey() {
        return this.vWorldApiKey;
    }
}