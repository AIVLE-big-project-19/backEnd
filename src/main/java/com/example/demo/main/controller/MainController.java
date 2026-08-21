package com.example.demo.main.controller;

import com.example.demo.main.service.MainService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;

@Controller
public class MainController {

    private final MainService mainService;

    public MainController(MainService mainService) {
        this.mainService = mainService;
    }

    @GetMapping("/map/search")
    @ResponseBody
    public String searchProxy(@RequestParam("keyword") String keyword) {
        return mainService.searchVWorldPlace(keyword);
    }

    @GetMapping("/map/address")
    @ResponseBody
    public String addressProxy(@RequestParam("point") String point) {
        return mainService.getVWorldAddress(point);
    }

    @GetMapping("/vworld-key")
    public ResponseEntity<Map<String, String>> getVworldKey() {
        Map<String, String> response = new HashMap<>();
        response.put("apiKey", mainService.getVWorldApiKey());
        return ResponseEntity.ok(response);
    }
}