package com.example.demo.fastApi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FastApiSolarRequest {
    private String image_url;
    private Double min_conf;
    private Integer min_pixel_area;
}