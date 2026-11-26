package pqc.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvKeyProviderTest {

    private static void putKey(Map<String, String> env, String kid, String alg, KeyPair kp, boolean withPrivate) {
        String p = EnvKeyProvider.prefix(kid);
        env.put(p + "ALG", alg);
        env.put(p + "PUBLIC", Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        if (withPrivate) {
            env.put(p + "PRIVATE", Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));
        }
    }

    @Test
    void 변수_이름_규칙() {
        assertEquals("CRYPTO_KEY_GW_RSA_1_", EnvKeyProvider.prefix("gw-rsa-1"));
    }

    @Test
    void 세_종류_키를_읽어_서명과_키_교환에_쓸_수_있음() {
        Map<String, String> env = new HashMap<>();
        putKey(env, "corp-rsa-1", "RSA", TestKeys.RSA, true);
        putKey(env, "corp-mldsa-1", "ML-DSA", TestKeys.ML_DSA, true);
        putKey(env, "gw-rsa-1", "RSA", TestKeys.RSA_KEM, true);
        putKey(env, "gw-mlkem-1", "ML-KEM", TestKeys.ML_KEM, true);
        EnvKeyProvider keys = new EnvKeyProvider(env);

        assertArrayEquals(TestKeys.ML_DSA.getPublic().getEncoded(), keys.publicKey("corp-mldsa-1").getEncoded());

        byte[] data = "x".getBytes(StandardCharsets.UTF_8);
        Signer signer = new Signer(keys);
        assertDoesNotThrow(() -> signer.verify(CryptoMode.HYBRID, data, signer.sign(CryptoMode.HYBRID, data, TestKeys.SIGN_KIDS)));

        KeyExchange kx = new KeyExchange(keys);
        KeyExchange.Initiated sent = kx.initiate(CryptoMode.HYBRID, TestKeys.KEM_KIDS);
        assertArrayEquals(sent.sessionKey().getEncoded(), kx.accept(CryptoMode.HYBRID, sent.ciphertexts()).getEncoded());
    }

    @Test
    void 공개키만_등록하면_개인키_요청은_실패() {
        Map<String, String> env = new HashMap<>();
        putKey(env, "gw-mlkem-1", "ML-KEM", TestKeys.ML_KEM, false);
        EnvKeyProvider keys = new EnvKeyProvider(env);
        assertDoesNotThrow(() -> keys.publicKey("gw-mlkem-1"));
        assertThrows(CryptoException.class, () -> keys.privateKey("gw-mlkem-1"));
    }

    @Test
    void 잘못된_값은_변수_이름만_알리고_값은_노출하지_않음_T9() {
        String secretLooking = "c2VjcmV0LXZhbHVl";
        Map<String, String> env = Map.of(
                "CRYPTO_KEY_X_1_ALG", "RSA",
                "CRYPTO_KEY_X_1_PRIVATE", secretLooking);
        CryptoException e = assertThrows(CryptoException.class, () -> new EnvKeyProvider(env).privateKey("x-1"));
        assertFalse(e.getMessage().contains(secretLooking));
        assertEquals("개인키 형식 오류: CRYPTO_KEY_X_1_PRIVATE", e.getMessage());
    }

    @Test
    void 없는_키나_지원하지_않는_알고리즘은_실패() {
        assertThrows(CryptoException.class, () -> new EnvKeyProvider(Map.of()).publicKey("none"));
        Map<String, String> env = Map.of(
                "CRYPTO_KEY_E_1_ALG", "EC",
                "CRYPTO_KEY_E_1_PUBLIC", Base64.getEncoder().encodeToString(new byte[10]));
        assertThrows(CryptoException.class, () -> new EnvKeyProvider(env).publicKey("e-1"));
        Map<String, String> bad = Map.of("CRYPTO_KEY_B_1_ALG", "RSA", "CRYPTO_KEY_B_1_PUBLIC", "%%%");
        assertThrows(CryptoException.class, () -> new EnvKeyProvider(bad).publicKey("b-1"));
    }
}
