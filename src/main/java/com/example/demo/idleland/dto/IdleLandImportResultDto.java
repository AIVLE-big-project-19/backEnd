package com.example.demo.idleland.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class IdleLandImportResultDto {

    private int totalCount;
    private int landCount;
    private int buildingCount;
    private int unknownCount;
}
