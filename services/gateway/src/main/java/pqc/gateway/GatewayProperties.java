package pqc.gateway;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import pqc.crypto.CryptoMode;

/**
 * 게이트웨이 설정. 기업 정보는 게이트웨이가 DB에 닿지 못해서(DMZ, docs/infra/network.md) 설정으로 받는다.
 * 환경변수 예: GATEWAY_CORPORATIONS_1001_FLOOR=HYBRID, GATEWAY_CORPORATIONS_1001_KIDS=acme-rsa-1,acme-mldsa-1
 *
 * @param transferApiUrl 이체 API 주소(F2)
 * @param clockSkew      timestamp 허용 범위(앞뒤). 전문 규격 5분
 * @param nonceTtl       nonce 보관 기간. 전문 규격 10분
 * @param corporations   기업 ID별 최저 모드와 그 기업의 서명 키 kid 목록
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(
        URI transferApiUrl,
        @DefaultValue("5m") Duration clockSkew,
        @DefaultValue("10m") Duration nonceTtl,
        Map<Long, Corporation> corporations) {

    public GatewayProperties {
        corporations = corporations == null ? Map.of() : Map.copyOf(corporations);
        if (nonceTtl.compareTo(clockSkew.multipliedBy(2)) < 0) {
            // timestamp가 유효한 동안(앞뒤 clockSkew)은 nonce가 남아 있어야 재전송을 막는다
            throw new IllegalArgumentException("nonceTtl은 clockSkew의 2배 이상이어야 한다");
        }
    }

    /**
     * @param floor 허용 최저 모드(crypto_floor)
     * @param kids  이 기업의 서명 키 번호. 다른 기업의 kid로 서명한 요청을 막는다
     */
    public record Corporation(CryptoMode floor, Set<String> kids) {
        public Corporation {
            if (floor == null) {
                throw new IllegalArgumentException("floor 없음");
            }
            kids = kids == null ? Set.of() : Set.copyOf(kids);
        }
    }
}
