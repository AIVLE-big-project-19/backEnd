package com.example.demo.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SiteInfo {

    @JsonProperty("site_id")
    private String siteId;

    @JsonProperty("site_name")
    private String siteName;

    private String address;

    @JsonProperty("space_type")
    private String spaceType;

    @JsonProperty("total_area")
    private Double totalArea;

    @JsonProperty("available_area")
    private Double availableArea;

    @JsonProperty("availability_rate_percent")
    private Double availabilityRatePercent;

    @JsonProperty("owner_agency")
    private String ownerAgency;

    @JsonProperty("created_at")
    private String createdAt;
}
