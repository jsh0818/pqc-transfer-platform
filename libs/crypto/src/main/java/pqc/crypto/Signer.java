package pqc.crypto;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 모드에 맞는 서명을 만들고 검증한다.
 * 서명 대상은 JCS(RFC 8785)로 정규화한 바이트이며, 정규화는 호출하는 쪽(게이트웨이)이 한다.
 */
public final class Signer {
    private final KeyProvider keys;

    public Signer(KeyProvider keys) {
        this.keys = keys;
    }

    /**
     * 모드가 요구하는 알고리즘마다 서명을 하나씩 만든다.
     *
     * @param kids 알고리즘별로 쓸 개인키의 kid. 모드가 요구하는 알고리즘이 모두 있어야 한다.
     */
    public List<SignatureValue> sign(CryptoMode mode, byte[] data, Map<SignatureAlg, String> kids) {
        List<SignatureValue> result = new ArrayList<>();
        for (SignatureAlg alg : mode.requiredSignatureAlgs()) {
            String kid = kids.get(alg);
            if (kid == null) {
                throw new CryptoException(mode + " 서명에 필요한 kid 없음: " + alg.id());
            }
            try {
                Signature s = newSignature(alg);
                s.initSign(keys.privateKey(kid), Providers.RANDOM);
                s.update(data);
                result.add(new SignatureValue(alg.id(), kid, s.sign()));
            } catch (GeneralSecurityException e) {
                throw new CryptoException(alg.id() + " 서명 실패: kid=" + kid, e);
            }
        }
        return result;
    }

    /**
     * 서명 알고리즘 집합이 모드가 요구하는 것과 정확히 같고, 모든 서명이 유효해야 통과한다.
     * 하나라도 어긋나면 VerificationException(게이트웨이에서 E1001).
     */
    public void verify(CryptoMode mode, byte[] data, List<SignatureValue> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            throw new VerificationException("서명 없음");
        }
        Map<SignatureAlg, SignatureValue> byAlg = new EnumMap<>(SignatureAlg.class);
        for (SignatureValue sig : signatures) {
            SignatureAlg alg = SignatureAlg.fromId(sig.alg());
            if (byAlg.put(alg, sig) != null) {
                throw new VerificationException("같은 알고리즘의 서명이 두 번 있음: " + alg.id());
            }
        }
        Set<SignatureAlg> required = mode.requiredSignatureAlgs();
        if (!byAlg.keySet().equals(required)) {
            throw new VerificationException(mode + " 모드는 서명 " + ids(required) + "가 필요한데 "
                    + ids(EnumSet.copyOf(byAlg.keySet())) + "가 왔음");
        }
        for (SignatureValue sig : byAlg.values()) {
            SignatureAlg alg = SignatureAlg.fromId(sig.alg());
            PublicKey publicKey;
            try {
                publicKey = keys.publicKey(sig.kid());
            } catch (CryptoException e) {
                throw new VerificationException("등록되지 않은 kid: " + sig.kid(), e);
            }
            boolean ok;
            try {
                Signature s = newSignature(alg);
                s.initVerify(publicKey);
                s.update(data);
                ok = s.verify(sig.value());
            } catch (GeneralSecurityException e) {
                throw new VerificationException(alg.id() + " 서명 검증 불가: kid=" + sig.kid(), e);
            }
            if (!ok) {
                throw new VerificationException(alg.id() + " 서명 불일치: kid=" + sig.kid());
            }
        }
    }

    private static Signature newSignature(SignatureAlg alg) throws GeneralSecurityException {
        return switch (alg) {
            case RSA -> Signature.getInstance("SHA256withRSA", Providers.BC); // nosemgrep: rsa-jca (LEGACY·HYBRID용 의도된 RSA)
            case ML_DSA -> Signature.getInstance("ML-DSA", Providers.BC);
        };
    }

    private static List<String> ids(Set<SignatureAlg> algs) {
        return algs.stream().map(SignatureAlg::id).toList();
    }
}
