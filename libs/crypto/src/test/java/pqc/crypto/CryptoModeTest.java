package pqc.crypto;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoModeTest {

    @Test
    void 모드별_필요한_알고리즘() {
        assertEquals(EnumSet.of(SignatureAlg.RSA), CryptoMode.LEGACY.requiredSignatureAlgs());
        assertEquals(EnumSet.of(SignatureAlg.RSA, SignatureAlg.ML_DSA), CryptoMode.HYBRID.requiredSignatureAlgs());
        assertEquals(EnumSet.of(SignatureAlg.ML_DSA), CryptoMode.PQC.requiredSignatureAlgs());

        assertEquals(EnumSet.of(KemAlg.RSA_OAEP), CryptoMode.LEGACY.requiredKemAlgs());
        assertEquals(EnumSet.of(KemAlg.RSA_OAEP, KemAlg.ML_KEM), CryptoMode.HYBRID.requiredKemAlgs());
        assertEquals(EnumSet.of(KemAlg.ML_KEM), CryptoMode.PQC.requiredKemAlgs());
    }

    @Test
    void 최저_모드_비교_T2() {
        assertTrue(CryptoMode.HYBRID.isAtLeast(CryptoMode.HYBRID));
        assertTrue(CryptoMode.PQC.isAtLeast(CryptoMode.HYBRID));
        assertFalse(CryptoMode.LEGACY.isAtLeast(CryptoMode.HYBRID));
        assertTrue(CryptoMode.LEGACY.isAtLeast(CryptoMode.LEGACY));
        assertFalse(CryptoMode.HYBRID.isAtLeast(CryptoMode.PQC));
    }

    @Test
    void 반환한_집합을_바꿔도_모드는_바뀌지_않음() {
        CryptoMode.HYBRID.requiredSignatureAlgs().clear();
        assertEquals(2, CryptoMode.HYBRID.requiredSignatureAlgs().size());
    }
}
