package pqc.gateway;

import java.nio.ByteBuffer;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import pqc.crypto.CryptoException;
import pqc.crypto.KeyProvider;
import pqc.gateway.GatewayProperties.Corporation;

/**
 * 기업의 최저 모드와 서명 키(kid) 목록을 찾는다.
 * 지금은 설정에서 읽는다. 정책 변경(T13)이 바로 반영되어야 하면 이체 API 조회로 구현을 바꾼다.
 */
public interface CorporationDirectory {

    Optional<Corporation> find(long corporationId);

    /**
     * 설정의 기업 목록. 시작할 때 키 설정을 확인하고, 틀리면 시작하지 않는다.
     * 그러지 않으면 설정 실수가 요청마다 E1001(서명 실패)로만 보여 공격 시도와 구별되지 않는다.
     * <ul>
     * <li>모든 기업에 kid가 1개 이상 있고, 모든 kid의 공개키를 읽을 수 있어야 한다</li>
     * <li>서로 다른 kid가 같은 공개키를 가리키면 안 된다. 같은 kid를 두 기업에 넣거나, 환경변수 이름이 겹치는 kid
     *     (EnvKeyProvider는 대소문자와 '-', '.', '_'를 구분하지 않음. 예: acme-rsa와 ACME.RSA)를 쓰면
     *     한 기업의 키로 다른 기업 요청을 서명할 수 있게 된다</li>
     * </ul>
     * 오류 메시지에는 기업 ID, kid, 환경변수 이름만 넣고 키 값은 넣지 않는다(T9).
     */
    static CorporationDirectory of(GatewayProperties properties, KeyProvider keys) {
        Map<ByteBuffer, String> owners = new HashMap<>();
        new TreeMap<>(properties.corporations()).forEach((id, corporation) -> {
            if (corporation.kids().isEmpty()) {
                throw new IllegalStateException("기업 " + id + "에 서명 키(kid)가 없음");
            }
            corporation.kids().stream().sorted().forEach(kid -> {
                String owner = "기업 " + id + "의 kid " + kid;
                PublicKey key;
                try {
                    key = keys.publicKey(kid);
                } catch (CryptoException e) {
                    throw new IllegalStateException(owner + "의 공개키를 읽을 수 없음: " + e.getMessage(), e);
                }
                String other = owners.putIfAbsent(ByteBuffer.wrap(key.getEncoded()), owner);
                if (other != null) {
                    throw new IllegalStateException(other + "와 " + owner + "가 같은 공개키를 가리킴");
                }
            });
        });
        return id -> Optional.ofNullable(properties.corporations().get(id));
    }
}
