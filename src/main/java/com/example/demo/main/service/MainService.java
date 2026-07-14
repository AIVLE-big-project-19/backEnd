package com.example.demo.main.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class MainService {

    private final String vWorldApiKey = "0559A559-131E-35CB-90E4-93B1B10CA3E9"; //추후 properties나 evn로 빼기

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

    public String getVWorldApiKey() {
        return this.vWorldApiKey;
    }
}