package pqc.crypto;

import java.util.EnumSet;
import java.util.Set;

/**
 * 암호 모드. 모드는 "어떤 알고리즘 조합이 필요한가"만 정한다.
 * 선언 순서가 강도 순서다(LEGACY &lt; HYBRID &lt; PQC).
 */
public enum CryptoMode {
    LEGACY(EnumSet.of(SignatureAlg.RSA), EnumSet.of(KemAlg.RSA_OAEP)),
    HYBRID(EnumSet.of(SignatureAlg.RSA, SignatureAlg.ML_DSA), EnumSet.of(KemAlg.RSA_OAEP, KemAlg.ML_KEM)),
    PQC(EnumSet.of(SignatureAlg.ML_DSA), EnumSet.of(KemAlg.ML_KEM));

    private final Set<SignatureAlg> signatureAlgs;
    private final Set<KemAlg> kemAlgs;

    CryptoMode(Set<SignatureAlg> signatureAlgs, Set<KemAlg> kemAlgs) {
        this.signatureAlgs = signatureAlgs;
        this.kemAlgs = kemAlgs;
    }

    /** 이 모드에서 반드시 있어야 하는 서명 알고리즘. 이보다 많거나 적으면 거부한다. */
    public Set<SignatureAlg> requiredSignatureAlgs() {
        return EnumSet.copyOf(signatureAlgs);
    }

    /** 이 모드에서 반드시 있어야 하는 키 교환 알고리즘. */
    public Set<KemAlg> requiredKemAlgs() {
        return EnumSet.copyOf(kemAlgs);
    }

    /** 기업의 허용 최저 모드(crypto_floor) 이상인지. 아니면 게이트웨이가 E1003으로 거부한다. */
    public boolean isAtLeast(CryptoMode floor) {
        return this.ordinal() >= floor.ordinal();
    }
}
