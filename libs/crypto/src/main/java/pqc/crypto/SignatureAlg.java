package pqc.crypto;

/** 서명 알고리즘. id는 전문 규격 signatures[].alg 값과 같다. */
public enum SignatureAlg {
    RSA("RSA"),
    ML_DSA("ML-DSA");

    private final String id;

    SignatureAlg(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static SignatureAlg fromId(String id) {
        for (SignatureAlg alg : values()) {
            if (alg.id.equals(id)) {
                return alg;
            }
        }
        throw new VerificationException("알 수 없는 서명 알고리즘: " + id);
    }
}
