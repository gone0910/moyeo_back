package com.example.capstone.plan.service;

import com.example.capstone.plan.dto.common.KakaoPlaceDto;
import com.example.capstone.plan.entity.City;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class KakaoMapClient {

    @Value("${kakao.api.key}")
    private String kakaoApiKey;

    private final ObjectMapper objectMapper;
    private final RestTemplateBuilder restTemplateBuilder;

    private RestTemplate getRestTemplate() {
        return restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(10))
                .build();
    }

    // 위도경도 → City enum
    public City getCityFromLatLng(double lat, double lng) {
        try {
            String url = String.format(
                    "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json?x=%f&y=%f",
                    lng, lat
            );

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoApiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = getRestTemplate()
                    .exchange(url, HttpMethod.GET, entity, String.class);

            JsonNode documents = objectMapper.readTree(response.getBody()).get("documents");
            if (documents != null && documents.size() > 0) {
                String region2 = documents.get(0).path("region_2depth_name").asText();
                for (City city : City.values()) {
                    if (region2.contains(city.getDisplayName())) {
                        return city;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("KakaoMap 좌표 → City 변환 실패", e);
        }
        throw new RuntimeException("좌표에 대응하는 City를 찾을 수 없습니다");
    }

    // GPT 장소 정제용
    public KakaoPlaceDto searchPlaceFromGpt(String gptName, String locationName, String categoryCode) {
        if (locationName != null && !locationName.isBlank()) {
            KakaoPlaceDto result = searchPlaceWithCategory(locationName, categoryCode);
            if (result != null) return result;
        }
        return searchPlaceWithCategory(gptName, categoryCode);
    }

    // 일반 키워드 검색
    public KakaoPlaceDto searchPlace(String keyword) {
        return searchPlaceWithCategory(keyword, null);
    }

    // 단일 장소 검색
    public KakaoPlaceDto searchPlaceWithCategory(String keyword, String categoryCode) {
        try {
            String url = "https://dapi.kakao.com/v2/local/search/keyword.json?query=" + keyword;
            if (categoryCode != null && !categoryCode.isBlank()) {
                url += "&category_group_code=" + categoryCode;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoApiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = getRestTemplate()
                    .exchange(url, HttpMethod.GET, entity, String.class);

            JsonNode documents = objectMapper.readTree(response.getBody()).get("documents");

            if (documents != null && documents.size() > 0) {
                for (JsonNode doc : documents) {
                    String docCategory = doc.path("category_group_code").asText("");
                    if (categoryCode == null || categoryCode.isBlank() || categoryCode.equals(docCategory)) {
                        return extractPlaceFromJson(doc);
                    }
                }
                return extractPlaceFromJson(documents.get(0));
            }
        } catch (Exception e) {
            throw new RuntimeException("KakaoMap 검색 중 오류 발생", e);
        }
        return null;
    }

    // 다중 장소 검색
    public List<KakaoPlaceDto> searchPlacesWithCategory(String keyword, String categoryCode) {
        try {
            String url = "https://dapi.kakao.com/v2/local/search/keyword.json?query=" + keyword;
            if (categoryCode != null && !categoryCode.isBlank()) {
                url += "&category_group_code=" + categoryCode;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoApiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = getRestTemplate()
                    .exchange(url, HttpMethod.GET, entity, String.class);

            JsonNode documents = objectMapper.readTree(response.getBody()).get("documents");
            List<KakaoPlaceDto> result = new ArrayList<>();

            if (documents != null && documents.size() > 0) {
                for (JsonNode doc : documents) {
                    result.add(extractPlaceFromJson(doc));
                }
            }

            return result;

        } catch (Exception e) {
            throw new RuntimeException("KakaoMap 다중 장소 검색 중 오류 발생", e);
        }
    }

    // GPS 기반 다중 장소 검색
    public List<KakaoPlaceDto> searchPlacesByCategory(double lat, double lng, String categoryCode) {
        try {
            String url = String.format(
                    "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=%s&x=%f&y=%f&radius=5000&sort=distance",
                    categoryCode, lng, lat
            );

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoApiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = getRestTemplate()
                    .exchange(url, HttpMethod.GET, entity, String.class);

            JsonNode documents = objectMapper.readTree(response.getBody()).get("documents");
            List<KakaoPlaceDto> result = new ArrayList<>();

            if (documents != null && documents.size() > 0) {
                for (JsonNode doc : documents) {
                    result.add(extractPlaceFromJson(doc));
                }
            }

            return result;

        } catch (Exception e) {
            throw new RuntimeException("KakaoMap 좌표 기반 장소 검색 중 오류 발생", e);
        }
    }

    // 재조회 전용: City + 카테고리 코드 + 개수 제한
    public List<KakaoPlaceDto> searchTopPlacesByCityAndCategory(City city, String categoryCode, int limit) {
        KakaoPlaceDto cityCenter = searchPlace(city.getDisplayName());
        if (cityCenter == null) {
            throw new RuntimeException("도시 중심 좌표 검색 실패: " + city.getDisplayName());
        }
        return searchTopPlacesByCategory(cityCenter.getLatitude(), cityCenter.getLongitude(), categoryCode, limit);
    }

    // 내부 재조회 로직
    public List<KakaoPlaceDto> searchTopPlacesByCategory(double lat, double lng, String categoryCode, int limit) {
        try {
            String url = String.format(
                    "https://dapi.kakao.com/v2/local/search/category.json?category_group_code=%s&x=%f&y=%f&radius=5000&sort=distance",
                    categoryCode, lng, lat
            );

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoApiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = getRestTemplate()
                    .exchange(url, HttpMethod.GET, entity, String.class);

            JsonNode documents = objectMapper.readTree(response.getBody()).get("documents");
            List<KakaoPlaceDto> result = new ArrayList<>();

            if (documents != null && documents.size() > 0) {
                for (JsonNode doc : documents) {
                    result.add(extractPlaceFromJson(doc));
                    if (result.size() >= limit) break;
                }
            }

            return result;

        } catch (Exception e) {
            throw new RuntimeException("KakaoMap 재조회 전용 검색 오류", e);
        }
    }
    public KakaoPlaceDto searchPlaceByCoordinate(String keyword, double lat, double lng) {
        try {
            String url = String.format(
                    "https://dapi.kakao.com/v2/local/search/keyword.json?query=%s&x=%f&y=%f&radius=1000",
                    keyword, lng, lat
            );

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "KakaoAK " + kakaoApiKey);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = getRestTemplate()
                    .exchange(url, HttpMethod.GET, entity, String.class);

            JsonNode documents = objectMapper.readTree(response.getBody()).get("documents");

            if (documents != null && documents.size() > 0) {
                return extractPlaceFromJson(documents.get(0)); // 첫 결과 반환
            }

            return null;

        } catch (Exception e) {
            throw new RuntimeException("KakaoMap 좌표 기반 검색 오류", e);
        }
    }


    // JSON → DTO 변환
    private KakaoPlaceDto extractPlaceFromJson(JsonNode doc) {
        String name = doc.path("place_name").asText();
        double lat = doc.path("y").asDouble();
        double lon = doc.path("x").asDouble();
        String roadAddress = doc.path("road_address_name").asText();
        String jibunAddress = doc.path("address_name").asText();

        String address = (roadAddress != null && !roadAddress.isBlank()) ? roadAddress :
                (jibunAddress != null && !jibunAddress.isBlank()) ? jibunAddress :
                        "주소 정보 없음";

        String phone = doc.path("phone").asText("");
        String category = doc.path("category_group_code").asText("");

        return new KakaoPlaceDto(name, lat, lon, address, phone, category);
    }

    public KakaoPlaceDto resolvePlaceFromHashtag(String hashtag) {
        KakaoPlaceDto result = searchPlace(hashtag);
        if (result != null) return result;
        return new KakaoPlaceDto(hashtag, 0.0, 0.0, "주소 정보 없음", "", "");
    }
}
