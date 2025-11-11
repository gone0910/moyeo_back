package com.example.capstone.plan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GeminiClient {

    @Value("${gemini.api-key}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.5-flash-lite}")
    private String model;

    private final ObjectMapper objectMapper;
    private final RestTemplateBuilder restTemplateBuilder;

    /**
     * 프롬프트를 보내고 Gemini가 반환한 JSON 문자열을 그대로 돌려줌.
     * - 동기 방식 (RestTemplate)
     * - 오류 발생 시 Gemini의 실제 응답 본문을 포함한 예외 메시지 반환
     */
    public String callGemini(String prompt) {
        RestTemplate restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))  // 연결 타임아웃
                .setReadTimeout(Duration.ofSeconds(60))    // 응답 타임아웃
                .build();

        try {
            // 요청 JSON 본문 구성
            String requestBody = objectMapper.writeValueAsString(
                    Map.of(
                            "contents", new Object[]{
                                    Map.of("parts", new Object[]{
                                            Map.of("text", prompt)
                                    })
                            },
                            "generationConfig", Map.of(
                                    "responseMimeType", "application/json",
                                    "temperature", 0.2
                            )
                    )
            );

            // HTTP 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isBlank()) {
                throw new RuntimeException("Gemini 응답이 비어 있습니다.");
            }

            JsonNode root = objectMapper.readTree(responseBody);

            // Gemini 차단 여부 확인
            JsonNode promptFeedback = root.path("promptFeedback");
            if (!promptFeedback.isMissingNode()) {
                String blockReason = promptFeedback.path("blockReason").asText(null);
                if (blockReason != null && !blockReason.isBlank()) {
                    throw new RuntimeException("Gemini 요청 차단: " + blockReason);
                }
            }

            // GPT 응답 내용 추출
            String text = root.path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asText(null);

            // 혹시 다른 구조일 경우 백업 탐색
            if (text == null) {
                for (JsonNode cand : root.path("candidates")) {
                    for (JsonNode part : cand.path("content").path("parts")) {
                        if (part.has("text")) {
                            text = part.get("text").asText();
                            break;
                        }
                    }
                    if (text != null) break;
                }
            }

            if (text == null) {
                throw new RuntimeException("Gemini 응답에서 text를 찾을 수 없습니다.\n응답 본문: " + responseBody);
            }

            return extractJsonBlock(text);

        } catch (HttpStatusCodeException e) {
            String body = e.getResponseBodyAsString();
            String message = String.format(
                    "Gemini API 호출 실패\n상태코드: %s\n에러본문: %s",
                    e.getStatusCode(), body
            );
            throw new RuntimeException(message, e);
        } catch (Exception e) {
            throw new RuntimeException("Gemini 응답 처리 중 예외 발생: " + e.getMessage(), e);
        }
    }

    /**
     * JsonNode로 직접 받고 싶을 때 사용
     */
    public JsonNode callGeminiAsJsonNode(String prompt) {
        try {
            String json = callGemini(prompt);
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Gemini JSON 파싱 실패: " + e.getMessage(), e);
        }
    }

    /**
     * 백틱, 코드펜스 제거 후 JSON만 추출
     */
    private String extractJsonBlock(String raw) {
        if (raw == null) return null;
        String s = raw.trim();

        s = s.replaceAll("^```(?:json)?\\s*", "");
        s = s.replaceAll("\\s*```\\s*$", "");
        s = s.replaceAll("^`+", "");
        s = s.replaceAll("`+$", "");

        int objStart = s.indexOf('{');
        int arrStart = s.indexOf('[');
        int start;
        if (objStart >= 0 && arrStart >= 0) start = Math.min(objStart, arrStart);
        else if (objStart >= 0) start = objStart;
        else start = arrStart;

        if (start < 0) return s;

        int objEnd = s.lastIndexOf('}');
        int arrEnd = s.lastIndexOf(']');
        int end = Math.max(objEnd, arrEnd);

        if (end >= start) {
            return s.substring(start, end + 1).trim();
        }
        return s;
    }
}
