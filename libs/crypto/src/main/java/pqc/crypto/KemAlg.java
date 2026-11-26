package pqc.crypto;

/** 키 교환 알고리즘. HKDF 결합 순서는 선언 순서(RSA_OAEP, ML_KEM)를 따른다. */
public enum KemAlg {
    RSA_OAEP("RSA-OAEP"),
    ML_KEM("ML-KEM");

    private final String id;

    KemAlg(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
