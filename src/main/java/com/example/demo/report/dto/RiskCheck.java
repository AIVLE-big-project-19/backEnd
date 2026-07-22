package com.example.demo.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RiskCheck {

    @JsonProperty("grid_connection")
    private String gridConnection;

    private String regulation;

    @JsonProperty("public_complaint")
    private String publicComplaint;
}
