package pqc.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignerTest {
    private static final byte[] DATA = "{\"amount\":\"1500000\",\"crypto_mode\":\"HYBRID\"}".getBytes(StandardCharsets.UTF_8);

    private final Signer corporation = new Signer(TestKeys.all());
    private final Signer gateway = new Signer(TestKeys.gatewaySide());

    private List<SignatureValue> sign(CryptoMode mode) {
        return corporation.sign(mode, DATA, TestKeys.SIGN_KIDS);
    }

    @ParameterizedTest
    @EnumSource(CryptoMode.class)
    void 모드별_서명_검증_성공(CryptoMode mode) {
        List<SignatureValue> sigs = sign(mode);
        assertEquals(mode.requiredSignatureAlgs().size(), sigs.size());
        assertDoesNotThrow(() -> gateway.verify(mode, DATA, sigs));
    }

    @ParameterizedTest
    @EnumSource(CryptoMode.class)
    void 본문_1비트_변조시_실패_T5(CryptoMode mode) {
        List<SignatureValue> sigs = sign(mode);
        for (int i = 0; i < DATA.length; i += 7) {
            byte[] tampered = TestKeys.flipBit(DATA, i);
            assertThrows(VerificationException.class, () -> gateway.verify(mode, tampered, sigs));
        }
    }

    @Test
    void HYBRID에서_RSA_서명만_틀려도_실패_T5() {
        assertThrows(VerificationException.class,
                () -> gateway.verify(CryptoMode.HYBRID, DATA, corrupt(sign(CryptoMode.HYBRID), "RSA")));
    }

    @Test
    void HYBRID에서_ML_DSA_서명만_틀려도_실패_T5() {
        assertThrows(VerificationException.class,
                () -> gateway.verify(CryptoMode.HYBRID, DATA, corrupt(sign(CryptoMode.HYBRID), "ML-DSA")));
    }

    @Test
    void HYBRID_요청에_RSA_서명만_붙이면_실패_다운그레이드_T2() {
        List<SignatureValue> legacyOnly = sign(CryptoMode.LEGACY);
        assertThrows(VerificationException.class, () -> gateway.verify(CryptoMode.HYBRID, DATA, legacyOnly));
    }

    @Test
    void HYBRID_서명에서_ML_DSA를_빼면_실패_다운그레이드_T2() {
        List<SignatureValue> sigs = new ArrayList<>(sign(CryptoMode.HYBRID));
        sigs.removeIf(s -> s.alg().equals("ML-DSA"));
        assertThrows(VerificationException.class, () -> gateway.verify(CryptoMode.HYBRID, DATA, sigs));
    }

    @Test
    void 모드보다_서명이_많아도_실패() {
        List<SignatureValue> hybrid = sign(CryptoMode.HYBRID);
        assertThrows(VerificationException.class, () -> gateway.verify(CryptoMode.PQC, DATA, hybrid));
        assertThrows(VerificationException.class, () -> gateway.verify(CryptoMode.LEGACY, DATA, hybrid));
    }

    @Test
    void 같은_알고리즘_서명이_두_번이면_실패() {
        List<SignatureValue> sigs = new ArrayList<>(sign(CryptoMode.PQC));
        sigs.add(sigs.get(0));
        assertThrows(VerificationException.class, () -> gateway.verify(CryptoMode.PQC, DATA, sigs));
    }

    @Test
    void 서명이_없으면_실패() {
        assertThrows(VerificationException.class, () -> gateway.verify(CryptoMode.PQC, DATA, List.of()));
        assertThrows(VerificationException.class, () -> gateway.verify(CryptoMode.PQC, DATA, null));
    }

    @Test
    void 알_수_없는_알고리즘이면_실패() {
        SignatureValue sig = sign(CryptoMode.PQC).get(0);
        List<SignatureValue> sigs = List.of(new SignatureValue("ECDSA", sig.kid(), sig.value()));
        assertThrows(VerificationException.class, () -> gateway.verify(CryptoMode.PQC, DATA, sigs));
    }

    @Test
    void 등록되지_않은_kid면_실패() {
        SignatureValue sig = sign(CryptoMode.PQC).get(0);
        List<SignatureValue> sigs = List.of(new SignatureValue(sig.alg(), "corp-mldsa-999", sig.value()));
        assertThrows(VerificationException.class, () -> gateway.verify(CryptoMode.PQC, DATA, sigs));
    }

    @Test
    void 다른_알고리즘의_키를_가리키는_kid면_실패() {
        SignatureValue sig = sign(CryptoMode.PQC).get(0);
        List<SignatureValue> sigs = List.of(new SignatureValue(sig.alg(), "corp-rsa-1", sig.value()));
        assertThrows(VerificationException.class, () -> gateway.verify(CryptoMode.PQC, DATA, sigs));
    }

    @Test
    void 서명에_필요한_kid가_없으면_서명_거부() {
        assertThrows(CryptoException.class,
                () -> corporation.sign(CryptoMode.HYBRID, DATA, java.util.Map.of(SignatureAlg.RSA, "corp-rsa-1")));
    }

    private static List<SignatureValue> corrupt(List<SignatureValue> sigs, String alg) {
        List<SignatureValue> result = new ArrayList<>();
        for (SignatureValue s : sigs) {
            result.add(s.alg().equals(alg) ? new SignatureValue(s.alg(), s.kid(), TestKeys.flipBit(s.value(), 10)) : s);
        }
        return result;
    }
}
