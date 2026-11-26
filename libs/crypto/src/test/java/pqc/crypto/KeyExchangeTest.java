package pqc.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.spec.MGF1ParameterSpec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeyExchangeTest {
    private static final byte[] AAD = "session-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BODY = "{\"amount\":\"1500000\"}".getBytes(StandardCharsets.UTF_8);

    private final KeyExchange corporation = new KeyExchange(TestKeys.corporationSide());
    private final KeyExchange gateway = new KeyExchange(TestKeys.gatewaySide());

    @ParameterizedTest
    @EnumSource(CryptoMode.class)
    void 모드별로_양쪽_세션_키가_같고_본문을_주고받을_수_있음(CryptoMode mode) {
        KeyExchange.Initiated sent = corporation.initiate(mode, TestKeys.KEM_KIDS);
        assertEquals(mode.requiredKemAlgs().size(), sent.ciphertexts().size());

        SecretKey received = gateway.accept(mode, sent.ciphertexts());
        assertArrayEquals(sent.sessionKey().getEncoded(), received.getEncoded());
        assertEquals(32, received.getEncoded().length);

        byte[] encrypted = Aead.encrypt(sent.sessionKey(), BODY, AAD);
        assertArrayEquals(BODY, Aead.decrypt(received, encrypted, AAD));
    }

    @Test
    void 할_때마다_다른_세션_키() {
        SecretKey a = corporation.initiate(CryptoMode.HYBRID, TestKeys.KEM_KIDS).sessionKey();
        SecretKey b = corporation.initiate(CryptoMode.HYBRID, TestKeys.KEM_KIDS).sessionKey();
        assertFalse(java.util.Arrays.equals(a.getEncoded(), b.getEncoded()));
    }

    /**
     * 위협 모델 T1 검증: 양자컴퓨터로 RSA 개인키를 얻은 공격자를 가정한다.
     * 공격자는 RSA 캡슐을 풀 수 있지만 ML-KEM 비밀은 모르므로 HYBRID 세션 키를 만들 수 없다.
     */
    @Test
    void HYBRID_세션_키는_RSA_비밀만으로_복원되지_않음_T1() throws Exception {
        KeyExchange.Initiated sent = corporation.initiate(CryptoMode.HYBRID, TestKeys.KEM_KIDS);
        KemCiphertext rsaCt = find(sent.ciphertexts(), KemAlg.RSA_OAEP);

        // 공격자: 깨진 RSA 개인키로 RSA 비밀을 얻는다
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding", Providers.BC); // nosemgrep: rsa-jca (시험용 공격자 흉내)
        rsa.init(Cipher.DECRYPT_MODE, TestKeys.RSA_KEM.getPrivate(),
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] rsaSecret = rsa.doFinal(rsaCt.value());
        assertEquals(32, rsaSecret.length);

        // ML-KEM 비밀을 모르는 상태에서 가능한 추측들
        List<byte[]> guesses = List.of(new byte[0], new byte[32], rsaSecret);
        for (byte[] guess : guesses) {
            Map<KemAlg, byte[]> secrets = new EnumMap<>(KemAlg.class);
            secrets.put(KemAlg.RSA_OAEP, rsaSecret.clone());
            secrets.put(KemAlg.ML_KEM, guess.clone());
            SecretKey attackerKey = KeyExchange.deriveSessionKey(CryptoMode.HYBRID, sent.ciphertexts(), secrets);
            assertFalse(java.util.Arrays.equals(sent.sessionKey().getEncoded(), attackerKey.getEncoded()));
        }

        // RSA 캡슐만 들고 LEGACY라고 주장해도 다른 키가 나온다(모드가 키 유도에 들어감)
        Map<KemAlg, byte[]> legacySecrets = new EnumMap<>(KemAlg.class);
        legacySecrets.put(KemAlg.RSA_OAEP, rsaSecret.clone());
        SecretKey asLegacy = KeyExchange.deriveSessionKey(CryptoMode.LEGACY, List.of(rsaCt), legacySecrets);
        assertFalse(java.util.Arrays.equals(sent.sessionKey().getEncoded(), asLegacy.getEncoded()));

        // 따라서 공격자는 HYBRID 본문을 풀 수 없다
        byte[] encrypted = Aead.encrypt(sent.sessionKey(), BODY, AAD);
        assertThrows(VerificationException.class, () -> Aead.decrypt(asLegacy, encrypted, AAD));
    }

    @Test
    void 다른_ML_KEM_개인키로는_다른_키가_나와_본문을_못_품_T1() {
        KeyExchange.Initiated sent = corporation.initiate(CryptoMode.HYBRID, TestKeys.KEM_KIDS);
        InMemoryKeyProvider wrongKeys = new InMemoryKeyProvider()
                .put("gw-rsa-1", TestKeys.RSA_KEM)
                .put("gw-mlkem-1", Keys.mlKem());
        SecretKey wrong = new KeyExchange(wrongKeys).accept(CryptoMode.HYBRID, sent.ciphertexts());
        assertFalse(java.util.Arrays.equals(sent.sessionKey().getEncoded(), wrong.getEncoded()));

        byte[] encrypted = Aead.encrypt(sent.sessionKey(), BODY, AAD);
        assertThrows(VerificationException.class, () -> Aead.decrypt(wrong, encrypted, AAD));
    }

    @Test
    void HYBRID_캡슐에서_ML_KEM을_빼면_거부_다운그레이드_T2() {
        KeyExchange.Initiated sent = corporation.initiate(CryptoMode.HYBRID, TestKeys.KEM_KIDS);
        List<KemCiphertext> rsaOnly = List.of(find(sent.ciphertexts(), KemAlg.RSA_OAEP));
        assertThrows(VerificationException.class, () -> gateway.accept(CryptoMode.HYBRID, rsaOnly));
    }

    @Test
    void HYBRID_캡슐을_LEGACY로_받아도_같은_키가_아님_T2() {
        KeyExchange.Initiated sent = corporation.initiate(CryptoMode.HYBRID, TestKeys.KEM_KIDS);
        List<KemCiphertext> rsaOnly = List.of(find(sent.ciphertexts(), KemAlg.RSA_OAEP));
        SecretKey asLegacy = gateway.accept(CryptoMode.LEGACY, rsaOnly);
        assertFalse(java.util.Arrays.equals(sent.sessionKey().getEncoded(), asLegacy.getEncoded()));
    }

    /** ADR-0001 규칙 2: 같은 캡슐·비밀이라도 모드 이름이 다르면 다른 키가 나온다. */
    @Test
    void 키_유도에_모드_이름이_들어감_T2() {
        List<KemCiphertext> cts = List.of(new KemCiphertext(KemAlg.ML_KEM, "gw-mlkem-1", new byte[]{1, 2, 3}));
        Map<KemAlg, byte[]> secrets = new EnumMap<>(KemAlg.class);
        secrets.put(KemAlg.ML_KEM, new byte[32]);
        SecretKey asPqc = KeyExchange.deriveSessionKey(CryptoMode.PQC, cts, Map.copyOf(secrets));
        SecretKey asHybrid = KeyExchange.deriveSessionKey(CryptoMode.HYBRID, cts, Map.copyOf(secrets));
        assertFalse(java.util.Arrays.equals(asPqc.getEncoded(), asHybrid.getEncoded()));
    }

    /** ADR-0001 규칙 2: 캡슐을 바꿔 끼우면(비밀이 같아도) 다른 키가 나온다. */
    @Test
    void 키_유도에_캡슐_바이트가_들어감() {
        Map<KemAlg, byte[]> secrets = Map.of(KemAlg.ML_KEM, new byte[32]);
        SecretKey a = KeyExchange.deriveSessionKey(CryptoMode.PQC,
                List.of(new KemCiphertext(KemAlg.ML_KEM, "gw-mlkem-1", new byte[]{1})), secrets);
        SecretKey b = KeyExchange.deriveSessionKey(CryptoMode.PQC,
                List.of(new KemCiphertext(KemAlg.ML_KEM, "gw-mlkem-1", new byte[]{2})), secrets);
        assertFalse(java.util.Arrays.equals(a.getEncoded(), b.getEncoded()));
    }

    @Test
    void 모드와_캡슐_조합이_다르면_거부() {
        List<KemCiphertext> hybrid = corporation.initiate(CryptoMode.HYBRID, TestKeys.KEM_KIDS).ciphertexts();
        assertThrows(VerificationException.class, () -> gateway.accept(CryptoMode.PQC, hybrid));
        assertThrows(VerificationException.class, () -> gateway.accept(CryptoMode.LEGACY, hybrid));
        assertThrows(VerificationException.class, () -> gateway.accept(CryptoMode.PQC, List.of()));
        assertThrows(VerificationException.class, () -> gateway.accept(CryptoMode.PQC, null));
    }

    @Test
    void 같은_알고리즘_캡슐이_두_번이면_거부() {
        List<KemCiphertext> pqc = new ArrayList<>(corporation.initiate(CryptoMode.PQC, TestKeys.KEM_KIDS).ciphertexts());
        pqc.add(pqc.get(0));
        assertThrows(VerificationException.class, () -> gateway.accept(CryptoMode.PQC, pqc));
    }

    @Test
    void RSA_캡슐이_변조되면_거부() {
        KemCiphertext ct = corporation.initiate(CryptoMode.LEGACY, TestKeys.KEM_KIDS).ciphertexts().get(0);
        KemCiphertext tampered = new KemCiphertext(ct.alg(), ct.kid(), TestKeys.flipBit(ct.value(), 5));
        assertThrows(VerificationException.class, () -> gateway.accept(CryptoMode.LEGACY, List.of(tampered)));
    }

    @Test
    void ML_KEM_캡슐이_변조되면_다른_키가_나와_본문을_못_품() {
        KeyExchange.Initiated sent = corporation.initiate(CryptoMode.PQC, TestKeys.KEM_KIDS);
        KemCiphertext ct = sent.ciphertexts().get(0);
        KemCiphertext tampered = new KemCiphertext(ct.alg(), ct.kid(), TestKeys.flipBit(ct.value(), 5));
        SecretKey received = gateway.accept(CryptoMode.PQC, List.of(tampered));
        assertFalse(java.util.Arrays.equals(sent.sessionKey().getEncoded(), received.getEncoded()));

        byte[] encrypted = Aead.encrypt(sent.sessionKey(), BODY, AAD);
        assertThrows(VerificationException.class, () -> Aead.decrypt(received, encrypted, AAD));
    }

    @Test
    void ML_KEM_캡슐_길이가_틀리면_거부() {
        KemCiphertext ct = corporation.initiate(CryptoMode.PQC, TestKeys.KEM_KIDS).ciphertexts().get(0);
        KemCiphertext shortCt = new KemCiphertext(ct.alg(), ct.kid(), java.util.Arrays.copyOf(ct.value(), 100));
        assertThrows(VerificationException.class, () -> gateway.accept(CryptoMode.PQC, List.of(shortCt)));
    }

    @Test
    void 등록되지_않은_kid면_거부() {
        KemCiphertext ct = corporation.initiate(CryptoMode.PQC, TestKeys.KEM_KIDS).ciphertexts().get(0);
        KemCiphertext unknown = new KemCiphertext(ct.alg(), "gw-mlkem-999", ct.value());
        assertThrows(VerificationException.class, () -> gateway.accept(CryptoMode.PQC, List.of(unknown)));
    }

    @Test
    void 키_교환에_필요한_kid가_없으면_시작_거부() {
        assertThrows(CryptoException.class,
                () -> corporation.initiate(CryptoMode.HYBRID, Map.of(KemAlg.ML_KEM, "gw-mlkem-1")));
    }

    private static KemCiphertext find(List<KemCiphertext> cts, KemAlg alg) {
        return cts.stream().filter(c -> c.alg() == alg).findFirst().orElseThrow();
    }
}
