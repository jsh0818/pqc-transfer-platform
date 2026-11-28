package pqc.gateway;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import pqc.crypto.CryptoMode;
import pqc.crypto.InMemoryKeyProvider;
import pqc.crypto.Keys;
import pqc.crypto.SignatureAlg;
import pqc.crypto.SignatureValue;
import pqc.crypto.Signer;
import pqc.gateway.GatewayProperties.Corporation;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 시험용 기업·키·요청. 기업 시스템 쪽에서 서명하는 과정을 그대로 흉내 낸다.
 * <ul>
 * <li>1001 (ACME): 최저 LEGACY, 키 acme-rsa, acme-mldsa</li>
 * <li>1002 (BETA): 최저 HYBRID, 키 beta-rsa, beta-mldsa</li>
 * </ul>
 */
final class Fixtures {

    static final Instant NOW = Instant.parse("2026-11-27T03:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final JsonMapper JSON = JsonMapper.builder().build();

    static final KeyPair ACME_RSA = Keys.rsa();
    static final KeyPair ACME_MLDSA = Keys.mlDsa();
    static final KeyPair BETA_RSA = Keys.rsa();
    static final KeyPair BETA_MLDSA = Keys.mlDsa();

    static final InMemoryKeyProvider KEYS = new InMemoryKeyProvider()
            .put("acme-rsa", ACME_RSA).put("acme-mldsa", ACME_MLDSA)
            .put("beta-rsa", BETA_RSA).put("beta-mldsa", BETA_MLDSA);
    static final Signer SIGNER = new Signer(KEYS);

    static final Map<Long, Corporation> CORPORATIONS = Map.of(
            1001L, new Corporation(CryptoMode.LEGACY, Set.of("acme-rsa", "acme-mldsa")),
            1002L, new Corporation(CryptoMode.HYBRID, Set.of("beta-rsa", "beta-mldsa")));

    private static int nonceSeq;

    private Fixtures() {
    }

    /** 서명 전 요청 본문. */
    static ObjectNode unsigned(CryptoMode mode, long corporationId) {
        ObjectNode envelope = JSON.createObjectNode();
        ObjectNode security = envelope.putObject("security");
        security.put("crypto_mode", mode.name());
        security.put("session_id", "s-7f3a9c21");
        security.put("timestamp", NOW.toString());
        security.put("nonce", String.format("nonce-%016d", ++nonceSeq));
        ObjectNode transfer = envelope.putObject("transfer");
        transfer.put("idempotency_key", "ACME-20261127-" + nonceSeq);
        transfer.put("corporation_id", corporationId);
        transfer.put("from_account_no", "1002345678901");
        transfer.put("to_bank_code", "088");
        transfer.put("to_account_no", "1109876543210");
        transfer.put("amount", "1500000");
        transfer.put("currency", "KRW");
        transfer.put("memo", "11월 거래대금");
        transfer.put("requested_at", "2026-11-27T02:59:58Z");
        return envelope;
    }

    /** 기업 시스템처럼 서명해서 signatures를 붙인다. kids는 알고리즘별 키 번호. */
    static ObjectNode sign(ObjectNode envelope, Map<SignatureAlg, String> kids) {
        CryptoMode mode = CryptoMode.valueOf(envelope.get("security").get("crypto_mode").asString());
        var signatures = SIGNER.sign(mode, SigningInput.of(envelope), kids);
        ArrayNode array = ((ObjectNode) envelope.get("security")).putArray("signatures");
        for (SignatureValue sig : signatures) {
            array.addObject().put("alg", sig.alg()).put("kid", sig.kid()).put("value", sig.valueBase64());
        }
        return envelope;
    }

    static Map<SignatureAlg, String> acmeKids() {
        return Map.of(SignatureAlg.RSA, "acme-rsa", SignatureAlg.ML_DSA, "acme-mldsa");
    }

    static Map<SignatureAlg, String> betaKids() {
        return Map.of(SignatureAlg.RSA, "beta-rsa", SignatureAlg.ML_DSA, "beta-mldsa");
    }

    /** 정상 서명된 ACME 요청. */
    static ObjectNode signed(CryptoMode mode) {
        return sign(unsigned(mode, 1001), acmeKids());
    }

    static ObjectNode security(ObjectNode envelope) {
        return (ObjectNode) envelope.get("security");
    }

    static ObjectNode transfer(ObjectNode envelope) {
        return (ObjectNode) envelope.get("transfer");
    }

    /** 메모리 nonce 저장소. 등록 횟수와 TTL을 기록한다. */
    static final class MemoryNonceStore implements NonceStore {
        final Map<String, Duration> keys = new ConcurrentHashMap<>();

        @Override
        public boolean register(String key, Duration ttl) {
            return keys.putIfAbsent(key, ttl) == null;
        }
    }
}
