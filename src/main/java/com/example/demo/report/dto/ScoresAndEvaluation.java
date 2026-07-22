package com.example.demo.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ScoresAndEvaluation {

    private String grade;

    @JsonProperty("total_score")
    private Integer totalScore;

    @JsonProperty("priority_rank")
    private String priorityRank;

    private String status;

    @JsonProperty("detail_scores")
    private DetailScores detailScores;

    @JsonProperty("xai_explanation")
    private XaiExplanation xaiExplanation;
}
