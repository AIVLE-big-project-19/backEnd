package com.example.demo.recommend.client.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class JobStatusResult {

    private String status; // queued | running | done | failed

    private String stage;

    private JobResult result; // status == "done"일 때만 채워짐

    private String error; // status == "failed"일 때만 채워짐
}
