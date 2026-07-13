package com.example.demo.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AvailabilityResponse {

    private boolean available;
}
