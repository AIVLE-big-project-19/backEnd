package com.example.demo.main.controller;

import com.example.demo.main.service.MainService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.*;

@Controller
public class MainController {

    private final MainService mainService;


    public MainController(MainService mainService) {
        this.mainService = mainService;
    }

    @GetMapping("/main") // 메인 화면 출력
    public String showMap(Model model) {
        model.addAttribute("vworldApiKey", mainService.getVWorldApiKey());


        model.addAttribute("locations", getSampleLocations());

        return "main";
    }

    @GetMapping("/api/map/search") //지도 검색

    @ResponseBody
    public String searchProxy(@RequestParam("keyword") String keyword) {
        return mainService.searchVWorldPlace(keyword);
    }

    private List<Map<String, Object>> getSampleLocations() {
        List<Map<String, Object>> sampleLocations = new ArrayList<>();
        return sampleLocations;
    }
}