package pqc.gateway;

import java.util.Optional;
import pqc.gateway.GatewayProperties.Corporation;

/**
 * 기업의 최저 모드와 서명 키(kid) 목록을 찾는다.
 * 지금은 설정에서 읽는다. 정책 변경(T13)이 바로 반영되어야 하면 이체 API 조회로 구현을 바꾼다.
 */
public interface CorporationDirectory {

    Optional<Corporation> find(long corporationId);

    static CorporationDirectory of(GatewayProperties properties) {
        return id -> Optional.ofNullable(properties.corporations().get(id));
    }
}
