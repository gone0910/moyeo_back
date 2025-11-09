package com.example.capstone.plan.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaceDetailReqDto {
    private String name;           // 장소명
    private String type;           // 장소 유형 (관광지, 숙소 등)
    private int estimatedCost;
    private double lat;            // 위도
    private double lng;            // 경도
}
