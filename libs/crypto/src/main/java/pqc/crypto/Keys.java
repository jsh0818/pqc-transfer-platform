package pqc.crypto;

import org.bouncycastle.jcajce.spec.MLDSAParameterSpec;
import org.bouncycastle.jcajce.spec.MLKEMParameterSpec;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

/** 모듈이 쓰는 강도로 키 쌍을 만든다(ADR-0001: RSA-2048, ML-DSA-65, ML-KEM-768). */
public final class Keys {
    public static final int RSA_BITS = 2048;

    private Keys() {
    }

    /** RSA-2048. LEGACY와 HYBRID의 서명·키 교환에 쓴다. */
    public static KeyPair rsa() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", Providers.BC); // nosemgrep: rsa-jca (LEGACY·HYBRID용 의도된 RSA)
            kpg.initialize(RSA_BITS, Providers.RANDOM);
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("RSA 키 생성 실패", e);
        }
    }

    /** ML-DSA-65 서명 키. */
    public static KeyPair mlDsa() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("ML-DSA", Providers.BC);
            kpg.initialize(MLDSAParameterSpec.ml_dsa_65, Providers.RANDOM);
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("ML-DSA 키 생성 실패", e);
        }
    }

    /** ML-KEM-768 키 교환 키. */
    public static KeyPair mlKem() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("ML-KEM", Providers.BC);
            kpg.initialize(MLKEMParameterSpec.ml_kem_768, Providers.RANDOM);
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new CryptoException("ML-KEM 키 생성 실패", e);
        }
    }
}
