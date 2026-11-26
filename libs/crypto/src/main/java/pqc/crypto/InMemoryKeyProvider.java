package pqc.crypto;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

/** 시험과 시뮬레이터용. 실제 환경에서는 EnvKeyProvider를 쓴다. */
public final class InMemoryKeyProvider implements KeyProvider {
    private final Map<String, PublicKey> publicKeys = new HashMap<>();
    private final Map<String, PrivateKey> privateKeys = new HashMap<>();

    public InMemoryKeyProvider put(String kid, KeyPair keyPair) {
        publicKeys.put(kid, keyPair.getPublic());
        privateKeys.put(kid, keyPair.getPrivate());
        return this;
    }

    /** 상대방의 공개키만 아는 경우(기업 시스템이 게이트웨이 공개키를 등록할 때). */
    public InMemoryKeyProvider putPublic(String kid, PublicKey publicKey) {
        publicKeys.put(kid, publicKey);
        return this;
    }

    @Override
    public PublicKey publicKey(String kid) {
        PublicKey key = publicKeys.get(kid);
        if (key == null) {
            throw new CryptoException("공개키 없음: kid=" + kid);
        }
        return key;
    }

    @Override
    public PrivateKey privateKey(String kid) {
        PrivateKey key = privateKeys.get(kid);
        if (key == null) {
            throw new CryptoException("개인키 없음: kid=" + kid);
        }
        return key;
    }
}
