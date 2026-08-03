package com.example.demo.idleland.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

// 후보지 위경도로 VWorld 정지영상(위성) 이미지를 받아온다.
// VWorld image 서비스는 bbox가 아니라 center+zoom만 지원하므로(직접 확인함),
// 표준 Web Mercator 타일 해상도 공식으로 zoom을 실제 커버 범위(extent3857)로 환산한다.
@Component
public class VWorldImageClient {

    // 지구 반경 기반 Web Mercator 타일 피라미드 상수 (zoom 0에서 256px가 지구 둘레를 덮음).
    private static final double BASE_RESOLUTION = 156543.03392804097;
    // VWorld image 서비스의 zoom 유효 범위는 7~18 (18 초과 시 INVALID_RANGE 에러, 실제 호출로 확인함).
    private static final int ZOOM = 18;
    private static final int IMAGE_SIZE_PX = 640;

    @Value("${VWORLD_API_KEY}")
    private String vWorldApiKey;

    private final RestTemplate restTemplate = new RestTemplate();

    public record VisionImageSource(byte[] imageBytes, String extent3857) {
    }

    public VisionImageSource fetchImage(double longitude, double latitude) {
        double metersPerPixel = BASE_RESOLUTION * Math.cos(Math.toRadians(latitude)) / Math.pow(2, ZOOM);
        double halfSizeMeters = metersPerPixel * IMAGE_SIZE_PX / 2.0;

        double centerX = longitude * 20037508.34 / 180.0;
        double centerY = Math.log(Math.tan((90 + latitude) * Math.PI / 360.0)) / (Math.PI / 180.0)
                * 20037508.34 / 180.0;

        String extent3857 = String.format(
                "%.3f,%.3f,%.3f,%.3f",
                centerX - halfSizeMeters, centerY - halfSizeMeters,
                centerX + halfSizeMeters, centerY + halfSizeMeters
        );

        String url = "https://api.vworld.kr/req/image"
                + "?service=image&request=getmap&format=png&basemap=PHOTO&crs=EPSG:4326"
                + "&center=" + longitude + "," + latitude
                + "&zoom=" + ZOOM
                + "&size=" + IMAGE_SIZE_PX + "," + IMAGE_SIZE_PX
                + "&key=" + vWorldApiKey;

        try {
            byte[] imageBytes = restTemplate.getForObject(URI.create(url), byte[].class);
            if (imageBytes == null || imageBytes.length == 0) {
                throw new CustomException(ErrorCode.VISION_ANALYSIS_FAILED, "VWorld 이미지 응답이 비어 있습니다.");
            }
            return new VisionImageSource(imageBytes, extent3857);
        } catch (CustomException e) {
            throw e;
        } catch (Exception e) {
            throw new CustomException(ErrorCode.VISION_ANALYSIS_FAILED, "VWorld 이미지 조회 실패: " + e.getMessage());
        }
    }
}
