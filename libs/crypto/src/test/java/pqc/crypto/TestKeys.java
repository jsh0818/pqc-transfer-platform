package pqc.crypto;

import java.security.KeyPair;
import java.util.Map;

/** 시험용 키. 키 생성이 느리므로 한 번만 만든다. */
final class TestKeys {
    static final KeyPair RSA = Keys.rsa();
    static final KeyPair ML_DSA = Keys.mlDsa();
    static final KeyPair RSA_KEM = Keys.rsa();
    static final KeyPair ML_KEM = Keys.mlKem();

    static final Map<SignatureAlg, String> SIGN_KIDS = Map.of(SignatureAlg.RSA, "corp-rsa-1", SignatureAlg.ML_DSA, "corp-mldsa-1");
    static final Map<KemAlg, String> KEM_KIDS = Map.of(KemAlg.RSA_OAEP, "gw-rsa-1", KemAlg.ML_KEM, "gw-mlkem-1");

    private TestKeys() {
    }

    /** 서명하는 쪽(기업)과 검증하는 쪽(게이트웨이)이 모두 가진 키. 개인키도 함께 넣어 양쪽 역할을 시험한다. */
    static InMemoryKeyProvider all() {
        return new InMemoryKeyProvider()
                .put("corp-rsa-1", RSA)
                .put("corp-mldsa-1", ML_DSA)
                .put("gw-rsa-1", RSA_KEM)
                .put("gw-mlkem-1", ML_KEM);
    }

    /** 기업 시스템이 아는 것: 게이트웨이의 공개키만. */
    static InMemoryKeyProvider corporationSide() {
        return new InMemoryKeyProvider()
                .putPublic("gw-rsa-1", RSA_KEM.getPublic())
                .putPublic("gw-mlkem-1", ML_KEM.getPublic());
    }

    /** 게이트웨이가 아는 것: 자기 개인키와 기업의 서명 공개키. */
    static InMemoryKeyProvider gatewaySide() {
        return new InMemoryKeyProvider()
                .put("gw-rsa-1", RSA_KEM)
                .put("gw-mlkem-1", ML_KEM)
                .putPublic("corp-rsa-1", RSA.getPublic())
                .putPublic("corp-mldsa-1", ML_DSA.getPublic());
    }

    static byte[] flipBit(byte[] data, int index) {
        byte[] copy = data.clone();
        copy[index] ^= 0x01;
        return copy;
    }
}
