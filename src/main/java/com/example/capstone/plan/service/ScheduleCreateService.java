package com.example.capstone.plan.service;

import com.example.capstone.plan.dto.common.GptPlaceDto;
import com.example.capstone.plan.dto.request.ScheduleCreateReqDto;
import com.example.capstone.plan.dto.response.ScheduleCreateResDto;
import com.example.capstone.plan.dto.response.ScheduleCreateResDto.DailyScheduleBlock;
import com.example.capstone.plan.dto.response.ScheduleCreateResDto.PlaceResponse;
import com.example.capstone.util.gpt.GptCreatePromptBuilder;
import com.example.capstone.util.gpt.GptCostPromptBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static java.sql.DriverManager.println;

@Service
@RequiredArgsConstructor
public class ScheduleCreateService {

    private final GptCreatePromptBuilder gptCreatePromptBuilder;
    private final ScheduleRefinerService scheduleRefinerService;
    private final KakaoMapClient kakaoMapClient;
    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;
    private final TmapRouteService tmapRouteService;
    private final GptCostPromptBuilder gptCostPromptBuilder;

    public ScheduleCreateResDto generateSchedule(ScheduleCreateReqDto request) {
        try {
            // 0. 날짜 유효성 검증 (startDate 확인)
            LocalDate today = LocalDate.now();

            if (request.getStartDate().isBefore(today)) {
                throw new IllegalArgumentException("오늘 이전 날짜로는 일정을 생성할 수 없습니다.");
            }

            // 1. GPT 프롬프트로 장소 구조 생성
            String prompt = gptCreatePromptBuilder.build(request);
            JsonNode root = geminiClient.callGeminiAsJsonNode(prompt).get("itinerary");


            // 2. GPT 응답 → 날짜별 GptPlaceDto 맵으로 변환
            Map<String, List<GptPlaceDto>> gptMap = new LinkedHashMap<>();
            for (JsonNode dayNode : root) {
                String date = dayNode.get("date").asText();
                List<GptPlaceDto> places = new ArrayList<>();
                for (JsonNode placeNode : dayNode.get("travelSchedule")) {
                    String type = placeNode.get("type").asText();
                    String hashtag = placeNode.get("name").asText();
                    places.add(GptPlaceDto.builder()
                            .name(hashtag)     // GPT가 생성한 장소 키워드
                            .type(type)        // 예: "관광지", "식사"
                            .location(null)    // GPT가 위도/경도 제공하지 않으므로 null 처리
                            .build());

                }
                gptMap.put(date, places);
            }

            // 3. KakaoMap 정제
            Map<String, List<PlaceResponse>> refinedMap = scheduleRefinerService.refine(gptMap);

            // 4. 이동시간 계산
            tmapRouteService.populateTimes(refinedMap);

            // 5. 예산 계산: 프롬프트 생성 → GPT 호출 → JSON 파싱 → estimatedCost 삽입
            String costPrompt = gptCostPromptBuilder.build(convertToPlaceDetailMap(refinedMap));
            JsonNode costJson = geminiClient.callGeminiAsJsonNode(costPrompt);

            Iterator<String> fieldNames = costJson.fieldNames();
            while (fieldNames.hasNext()) {
                String date = fieldNames.next();
                if (date.equals("totalEstimatedCost")) continue;

                JsonNode dateBlock = costJson.get(date);
                if (dateBlock == null || !dateBlock.has("travelSchedule")) continue;

                JsonNode travelSchedule = dateBlock.get("travelSchedule");
                if (travelSchedule == null || !travelSchedule.isArray()) continue;

                List<PlaceResponse> places = refinedMap.get(date);
                if (places == null) continue;

                for (JsonNode placeNode : travelSchedule) {
                    String gptName = Optional.ofNullable(placeNode.get("name")).map(JsonNode::asText).orElse(null);
                    int cost = Optional.ofNullable(placeNode.get("estimatedCost")).map(JsonNode::asInt).orElse(0);

                    if (gptName == null) continue;

                    String gptNameKey = gptName.replaceAll("\\s+", "").toLowerCase();

                    for (PlaceResponse place : places) {
                        String placeNameKey = place.getName().replaceAll("\\s+", "").toLowerCase();

                        if (placeNameKey.equals(gptNameKey)) {
                            place.setEstimatedCost(cost);
                            break;
                        }
                    }
                }
            }



            // 6. DailyScheduleBlock 응답 조립
            List<DailyScheduleBlock> dailyBlocks = new ArrayList<>();
            int dayCounter = 1;
            for (Map.Entry<String, List<PlaceResponse>> entry : refinedMap.entrySet()) {
                String day = dayCounter++ + "일차";
                String date = entry.getKey();
                int totalCost = entry.getValue().stream()
                        .mapToInt(PlaceResponse::getEstimatedCost)
                        .sum();

                dailyBlocks.add(DailyScheduleBlock.builder()
                        .day(day)
                        .date(date)
                        .totalEstimatedCost(totalCost)
                        .places(entry.getValue())
                        .build());
            }

            String destination = request.getDestination().getDisplayName();
            long nights = ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate());
            String title = destination + " " + nights + "박 " + (nights + 1) + "일 여행";

            return new ScheduleCreateResDto(title, request.getStartDate(), request.getEndDate(), dailyBlocks);

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("일정 생성 중 오류 발생: " + e.getMessage(), e);
        }
    }

    // PlaceResponse → PlaceDetailDto로 변환 (GptCostPromptBuilder에 맞춤)
    private Map<String, List<com.example.capstone.plan.dto.common.PlaceDetailDto>> convertToPlaceDetailMap(Map<String, List<PlaceResponse>> input) {
        Map<String, List<com.example.capstone.plan.dto.common.PlaceDetailDto>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<PlaceResponse>> entry : input.entrySet()) {
            List<com.example.capstone.plan.dto.common.PlaceDetailDto> converted = entry.getValue().stream()
                    .map(p -> com.example.capstone.plan.dto.common.PlaceDetailDto.builder()
                            .name(p.getName())
                            .type(p.getType())
                            .lat(p.getLat())
                            .lng(p.getLng())
                            .build()
                    ).toList();
            result.put(entry.getKey(), converted);
        }
        return result;
    }
}
