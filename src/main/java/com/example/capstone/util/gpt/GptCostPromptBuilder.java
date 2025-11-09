package com.example.capstone.util.gpt;

import com.example.capstone.plan.dto.common.PlaceDetailDto;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GptCostPromptBuilder {

    public String build(Map<String, List<PlaceDetailDto>> dayToPlaces) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
너는 여행 일정 비용을 예측하는 전문가야.
아래에 제공된 날짜별 장소 목록에 대해 각 장소의 `estimatedCost`와 하루치 `totalEstimatedCost`를 계산해줘.

[작성 규칙]
- 단위는 '원'
- 식사는 1인당 평균 식사비
- 숙소는 1박 기준 1인당 요금
- 숙소는 1박 기준 1인 요금으로 추정하며, 숙소 유형에 따라 다음 특성을 고려해:
  - **게스트하우스, 모텔 → 저가형 숙소**: 일반적으로 가장 저렴한 숙박 형태이며, 보통 1~4만원대 수준으로 형성됨
  - **펜션, 한옥스테이, 콘도 → 중간 가격대 숙소**: 지역·규모에 따라 다양하지만 일반적으로 5~9만원대
  - **호텔 → 고가 숙소**: 브랜드나 지역에 따라 다르지만 7~12만원대 수준
  - **리조트 → 최고급 숙소**: 프리미엄 시설을 갖춘 고급형, 보통 10~18만원대 이상
- 관광지는 입장료 또는 체험비용
- 무료인 경우 0원
- 하루 7개 항목 기준이며, 장소별 비용을 모두 합산한 값이 하루 예산
- 반드시 아래 JSON 형식으로 출력해줘:


  {
    "date": "2025-08-01",
    "totalEstimatedCost": 123000,
    "travelSchedule": [
      { "name": "곰막식당", "estimatedCost": 12000 },
      { "name": "한라산국립공원", "estimatedCost": 0 },
      ...
    ]
  },
  ...

[작성 규칙]
- 반드시 JSON **객체**로 반환해 (배열로 반환하지 마!)
- 날짜(`YYYY-MM-DD`)를 키로 하고, 그 안에 장소 리스트 배열을 담아줘
- 최상단은 다음처럼 구성해야 해:
                
[장소 목록]
""");

        for (Map.Entry<String, List<PlaceDetailDto>> entry : dayToPlaces.entrySet()) {
            String date = entry.getKey();
            List<PlaceDetailDto> places = entry.getValue();

            sb.append("■ 날짜: ").append(date).append("\n");
            for (PlaceDetailDto place : places) {
                sb.append("- 이름: ").append(place.getName())
                        .append(" / 유형: ").append(place.getType()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
