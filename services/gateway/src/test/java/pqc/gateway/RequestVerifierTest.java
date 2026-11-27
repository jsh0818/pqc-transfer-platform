package pqc.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pqc.gateway.Fixtures.security;
import static pqc.gateway.Fixtures.sign;
import static pqc.gateway.Fixtures.signed;
import static pqc.gateway.Fixtures.transfer;
import static pqc.gateway.Fixtures.unsigned;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import pqc.crypto.CryptoMode;
import pqc.crypto.SignatureAlg;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class RequestVerifierTest {

    Fixtures.MemoryNonceStore nonces;
    RequestVerifier verifier;

    @BeforeEach
    void setUp() {
        nonces = new Fixtures.MemoryNonceStore();
        verifier = new RequestVerifier(id -> java.util.Optional.ofNullable(Fixtures.CORPORATIONS.get(id)),
                Fixtures.SIGNER, nonces, Fixtures.CLOCK, Duration.ofMinutes(5), Duration.ofMinutes(10));
    }

    ResultCode reject(ObjectNode envelope) {
        return reject(envelope.toString());
    }

    ResultCode reject(String body) {
        return assertThrows(GatewayException.class, () -> verifier.verify(body)).code();
    }

    @Nested
    class 통과 {

        @ParameterizedTest
        @EnumSource(CryptoMode.class)
        void 세_모드_모두_정상_요청은_통과(CryptoMode mode) throws Exception {
            ObjectNode envelope = signed(mode);
            VerifiedTransfer v = verifier.verify(envelope.toString());

            assertEquals(1001, v.corporationId());
            assertEquals(mode, v.mode());
            assertEquals(transfer(envelope).toString(), v.transferJson());
            String expectedHash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(SigningInput.of(envelope)));
            assertEquals(expectedHash, v.requestHash());
        }

        @Test
        void nonce는_기업별로_10분_보관() {
            ObjectNode envelope = signed(CryptoMode.HYBRID);
            verifier.verify(envelope.toString());
            String key = "1001:" + security(envelope).get("nonce").asString();
            assertEquals(Map.of(key, Duration.ofMinutes(10)), nonces.keys);
        }

        @Test
        void timestamp_경계_5분은_통과() {
            ObjectNode envelope = unsigned(CryptoMode.LEGACY, 1001);
            security(envelope).put("timestamp", Fixtures.NOW.minus(Duration.ofMinutes(5)).toString());
            verifier.verify(sign(envelope, Fixtures.acmeKids()).toString());
        }

        @Test
        void 항목_순서와_공백이_달라도_같은_서명으로_통과() {
            ObjectNode envelope = signed(CryptoMode.LEGACY);
            ObjectNode reordered = Fixtures.JSON.createObjectNode();
            reordered.set("transfer", envelope.get("transfer"));
            reordered.set("security", envelope.get("security"));
            verifier.verify(Fixtures.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(reordered));
        }
    }

    @Nested
    class T2_다운그레이드 {

        @Test
        void 최저_모드_미만이면_E1003() {
            // BETA는 최저 HYBRID. 올바르게 서명했어도 LEGACY는 거부
            ObjectNode envelope = sign(unsigned(CryptoMode.LEGACY, 1002), Fixtures.betaKids());
            assertEquals(ResultCode.E1003, reject(envelope));
            assertTrue(nonces.keys.isEmpty(), "거부된 요청은 nonce를 쓰지 않는다");
        }

        @Test
        void 최저_모드_이상은_통과() {
            verifier.verify(sign(unsigned(CryptoMode.PQC, 1002), Fixtures.betaKids()).toString());
        }

        @Test
        void 서명_뒤에_모드를_낮추면_E1001() {
            // HYBRID로 서명한 뒤 중간에서 LEGACY로 바꾸고 RSA 서명만 남김: crypto_mode가 서명 대상이라 실패
            ObjectNode envelope = signed(CryptoMode.HYBRID);
            security(envelope).put("crypto_mode", "LEGACY");
            ArrayNode sigs = (ArrayNode) security(envelope).get("signatures");
            for (int i = sigs.size() - 1; i >= 0; i--) {
                if (!"RSA".equals(sigs.get(i).get("alg").asString())) {
                    sigs.remove(i);
                }
            }
            assertEquals(ResultCode.E1001, reject(envelope));
        }

        @Test
        void HYBRID인데_RSA_서명만_있으면_E1001() {
            ObjectNode envelope = signed(CryptoMode.HYBRID);
            ((ArrayNode) security(envelope).get("signatures")).remove(1);
            assertEquals(ResultCode.E1001, reject(envelope));
        }

        @Test
        void 알_수_없는_모드는_E1003() {
            ObjectNode envelope = signed(CryptoMode.LEGACY);
            security(envelope).put("crypto_mode", "NONE");
            assertEquals(ResultCode.E1003, reject(envelope));
        }
    }

    @Nested
    class T3_재전송 {

        @Test
        void 같은_요청을_다시_보내면_E1002() {
            String body = signed(CryptoMode.HYBRID).toString();
            verifier.verify(body);
            assertEquals(ResultCode.E1002, reject(body));
        }

        @ParameterizedTest
        @ValueSource(longs = {-301, 301})
        void timestamp가_5분을_넘으면_E1002(long seconds) {
            ObjectNode envelope = unsigned(CryptoMode.LEGACY, 1001);
            security(envelope).put("timestamp", Fixtures.NOW.plusSeconds(seconds).toString());
            assertEquals(ResultCode.E1002, reject(sign(envelope, Fixtures.acmeKids())));
            assertTrue(nonces.keys.isEmpty());
        }

        @Test
        void 서명이_틀린_요청은_nonce를_선점하지_못한다() {
            ObjectNode good = signed(CryptoMode.LEGACY);
            ObjectNode forged = good.deepCopy();
            transfer(forged).put("amount", "9999999");
            assertEquals(ResultCode.E1001, reject(forged));
            assertTrue(nonces.keys.isEmpty());
            verifier.verify(good.toString()); // 같은 nonce의 정상 요청은 통과
        }

        @Test
        void nonce_저장소_오류면_통과시키지_않고_E9000() {
            verifier = new RequestVerifier(id -> java.util.Optional.ofNullable(Fixtures.CORPORATIONS.get(id)),
                    Fixtures.SIGNER, (key, ttl) -> {
                        throw new IllegalStateException("Redis 연결 끊김");
                    }, Fixtures.CLOCK, Duration.ofMinutes(5), Duration.ofMinutes(10));
            assertEquals(ResultCode.E9000, reject(signed(CryptoMode.LEGACY)));
        }
    }

    @Nested
    class T5_변조와_인증 {

        @ParameterizedTest
        @ValueSource(strings = {"amount", "to_account_no", "from_account_no", "idempotency_key"})
        void 서명_뒤에_이체_내용을_바꾸면_E1001(String field) {
            ObjectNode envelope = signed(CryptoMode.HYBRID);
            String original = transfer(envelope).get(field).asString();
            transfer(envelope).put(field, original.substring(0, original.length() - 1)
                    + (original.endsWith("1") ? "2" : "1"));
            assertEquals(ResultCode.E1001, reject(envelope));
        }

        @Test
        void HYBRID에서_ML_DSA_서명만_틀려도_E1001() {
            ObjectNode envelope = signed(CryptoMode.HYBRID);
            ArrayNode sigs = (ArrayNode) security(envelope).get("signatures");
            for (var sig : sigs) {
                if ("ML-DSA".equals(sig.get("alg").asString())) {
                    byte[] value = java.util.Base64.getDecoder().decode(sig.get("value").asString());
                    value[0] ^= 1;
                    ((ObjectNode) sig).put("value", java.util.Base64.getEncoder().encodeToString(value));
                }
            }
            assertEquals(ResultCode.E1001, reject(envelope));
        }

        @Test
        void 다른_기업의_키로_서명하고_기업_ID를_사칭하면_E1001() {
            // BETA의 키로 올바르게 서명했지만 transfer.corporation_id는 ACME(1001)
            ObjectNode envelope = sign(unsigned(CryptoMode.HYBRID, 1001), Fixtures.betaKids());
            assertEquals(ResultCode.E1001, reject(envelope));
        }

        @Test
        void 등록되지_않은_기업은_E1001() {
            assertEquals(ResultCode.E1001, reject(sign(unsigned(CryptoMode.LEGACY, 9999), Fixtures.acmeKids())));
        }

        @Test
        void LEGACY에_서명이_더_붙어_있으면_E1001() {
            ObjectNode envelope = signed(CryptoMode.HYBRID);
            security(envelope).put("crypto_mode", "LEGACY");
            assertEquals(ResultCode.E1001, reject(envelope));
        }

        @Test
        void 서명에서_signatures만_빠지고_나머지는_모두_서명_대상() {
            ObjectNode envelope = signed(CryptoMode.LEGACY);
            security(envelope).put("session_id", "s-other");
            assertEquals(ResultCode.E1001, reject(envelope));
        }
    }

    @Nested
    class 형식_E1000 {

        @Test
        void JSON이_아니면_E1000() {
            assertEquals(ResultCode.E1000, reject("{not json"));
            assertEquals(ResultCode.E1000, reject(""));
            assertEquals(ResultCode.E1000, reject("[]"));
        }

        @Test
        void 같은_키가_두_번이면_E1000() {
            String body = signed(CryptoMode.LEGACY).toString();
            String dup = body.replace("\"amount\":\"1500000\"", "\"amount\":\"1500000\",\"amount\":\"9999999\"");
            assertTrue(dup.length() > body.length());
            assertEquals(ResultCode.E1000, reject(dup));
        }

        @Test
        void 정의되지_않은_항목이_있으면_E1000() {
            ObjectNode envelope = signed(CryptoMode.LEGACY);
            envelope.put("extra", 1);
            assertEquals(ResultCode.E1000, reject(envelope));
        }

        @Test
        void security_항목이_빠지면_E1000() {
            ObjectNode envelope = signed(CryptoMode.LEGACY);
            security(envelope).remove("nonce");
            assertEquals(ResultCode.E1000, reject(envelope));
        }

        @Test
        void nonce가_짧으면_E1000() {
            ObjectNode envelope = unsigned(CryptoMode.LEGACY, 1001);
            security(envelope).put("nonce", "short");
            assertEquals(ResultCode.E1000, reject(sign(envelope, Fixtures.acmeKids())));
        }

        @Test
        void 기업_ID가_정수가_아니거나_범위_밖이면_E1000() {
            ObjectNode envelope = signed(CryptoMode.LEGACY);
            transfer(envelope).put("corporation_id", "1001");
            assertEquals(ResultCode.E1000, reject(envelope));
            transfer(envelope).put("corporation_id", RequestVerifier.MAX_SAFE_INTEGER + 1);
            assertEquals(ResultCode.E1000, reject(envelope));
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "01500000", "1500000.5", "-1", "1234567890123456"})
        void 서명이_맞아도_금액_형식이_틀리면_E1000(String amount) {
            ObjectNode envelope = unsigned(CryptoMode.LEGACY, 1001);
            transfer(envelope).put("amount", amount);
            assertEquals(ResultCode.E1000, reject(sign(envelope, Fixtures.acmeKids())));
        }

        @Test
        void 금액이_숫자_타입이면_E1000() {
            ObjectNode envelope = unsigned(CryptoMode.LEGACY, 1001);
            transfer(envelope).put("amount", 1500000);
            assertEquals(ResultCode.E1000, reject(sign(envelope, Fixtures.acmeKids())));
        }

        @Test
        void 필수_항목이_빠지면_E1000() {
            ObjectNode envelope = unsigned(CryptoMode.LEGACY, 1001);
            transfer(envelope).remove("to_bank_code");
            assertEquals(ResultCode.E1000, reject(sign(envelope, Fixtures.acmeKids())));
        }

        @Test
        void 메모가_20자를_넘으면_E1000() {
            ObjectNode envelope = unsigned(CryptoMode.LEGACY, 1001);
            transfer(envelope).put("memo", "가".repeat(21));
            assertEquals(ResultCode.E1000, reject(sign(envelope, Fixtures.acmeKids())));
        }

        @Test
        void 서명값이_base64가_아니면_E1000() {
            ObjectNode envelope = signed(CryptoMode.LEGACY);
            ((ObjectNode) security(envelope).get("signatures").get(0)).put("value", "!!!");
            assertEquals(ResultCode.E1000, reject(envelope));
        }
    }
}
