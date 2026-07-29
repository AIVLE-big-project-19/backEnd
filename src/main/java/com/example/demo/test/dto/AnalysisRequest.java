package com.example.demo.test.dto; // 프로젝트 패키지 경로에 맞게 수정

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter
@NoArgsConstructor
public class AnalysisRequest {


    private Long userId;
   // @JsonProperty("task_id")
    private Long taskId;            // FastAPI가 요구하는 task_id

  //  @JsonProperty("image_url")
    private String imageUrl;         // FastAPI가 요구하는 image_url

   // @JsonProperty("analysis_type")
    private String analysisType;     // FastAPI가 요구하는 analysis_typ

    private String address;

}