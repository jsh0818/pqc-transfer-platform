package pqc.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import pqc.crypto.CryptoMode;
import pqc.crypto.EnvKeyProvider;
import pqc.crypto.KeyProvider;
import pqc.gateway.GatewayProperties.Corporation;

/** 시작할 때 키 설정 확인. 틀린 설정은 요청마다 E1001이 되는 대신 시작 단계에서 막힌다. */
class CorporationDirectoryTest {

    static final String ACME_RSA_PUBLIC = Base64.getEncoder().encodeToString(Fixtures.ACME_RSA.getPublic().getEncoded());

    static GatewayProperties properties(Map<Long, Corporation> corporations) {
        return new GatewayProperties(URI.create("http://transfer-api:8081"), Duration.ofMinutes(5), Duration.ofMinutes(10),
                corporations);
    }

    static IllegalStateException refused(Map<Long, Corporation> corporations, KeyProvider keys) {
        return assertThrows(IllegalStateException.class, () -> CorporationDirectory.of(properties(corporations), keys));
    }

    @Test
    void 키가_모두_있으면_기업을_찾는다() {
        CorporationDirectory directory = CorporationDirectory.of(properties(Fixtures.CORPORATIONS), Fixtures.KEYS);
        assertEquals(CryptoMode.HYBRID, directory.find(1002).orElseThrow().floor());
        assertTrue(directory.find(9999).isEmpty());
    }

    @Test
    void 공개키_환경변수가_빠지면_시작하지_않는다() {
        var env = new EnvKeyProvider(Map.of("CRYPTO_KEY_ACME_RSA_ALG", "RSA")); // _PUBLIC 없음
        var e = refused(Map.of(1001L, new Corporation(CryptoMode.LEGACY, Set.of("acme-rsa"))), env);
        assertTrue(e.getMessage().startsWith("기업 1001의 kid acme-rsa의 공개키를 읽을 수 없음"), e.getMessage());
        assertTrue(e.getMessage().contains("CRYPTO_KEY_ACME_RSA_PUBLIC"), e.getMessage());
    }

    @Test
    void kid가_없는_기업이_있으면_시작하지_않는다() {
        var e = refused(Map.of(1001L, new Corporation(CryptoMode.LEGACY, Set.of())), Fixtures.KEYS);
        assertEquals("기업 1001에 서명 키(kid)가 없음", e.getMessage());
    }

    @Test
    void 같은_kid를_두_기업에_넣으면_시작하지_않는다() {
        var e = refused(Map.of(
                1001L, new Corporation(CryptoMode.LEGACY, Set.of("acme-rsa")),
                1002L, new Corporation(CryptoMode.LEGACY, Set.of("acme-rsa"))), Fixtures.KEYS);
        assertEquals("기업 1001의 kid acme-rsa와 기업 1002의 kid acme-rsa가 같은 공개키를 가리킴", e.getMessage());
    }

    @Test
    void 환경변수_이름이_겹치는_kid는_시작하지_않고_키_값은_메시지에_넣지_않는다() {
        var env = new EnvKeyProvider(Map.of("CRYPTO_KEY_ACME_RSA_ALG", "RSA", "CRYPTO_KEY_ACME_RSA_PUBLIC", ACME_RSA_PUBLIC));
        var e = refused(Map.of(
                1001L, new Corporation(CryptoMode.LEGACY, Set.of("acme-rsa")),
                1002L, new Corporation(CryptoMode.LEGACY, Set.of("ACME.RSA"))), env);
        assertEquals("기업 1001의 kid acme-rsa와 기업 1002의 kid ACME.RSA가 같은 공개키를 가리킴", e.getMessage());
        assertFalse(e.getMessage().contains(ACME_RSA_PUBLIC.substring(0, 40)));
    }
}
