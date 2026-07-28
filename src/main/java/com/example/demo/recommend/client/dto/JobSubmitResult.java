package com.example.demo.recommend.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class JobSubmitResult {

    @JsonProperty("job_id")
    private String jobId;
}
