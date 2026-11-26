package pqc.crypto;

import org.junit.jupiter.api.Test;

import java.security.interfaces.RSAPublicKey;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ADR-0001의 알고리즘 강도가 실제 키와 같은지 확인한다. */
class KeysTest {

    @Test
    void RSA는_2048비트() {
        assertEquals(2048, ((RSAPublicKey) TestKeys.RSA.getPublic()).getModulus().bitLength());
    }

    @Test
    void ML_DSA는_65() {
        assertEquals("ML-DSA-65", TestKeys.ML_DSA.getPublic().getAlgorithm());
    }

    @Test
    void ML_KEM은_768() {
        assertEquals("ML-KEM-768", TestKeys.ML_KEM.getPublic().getAlgorithm());
    }
}
