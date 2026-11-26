package pqc.crypto;

import java.util.Arrays;
import java.util.Objects;

/** 키 교환 캡슐 하나: 어떤 알고리즘으로, 어느 키(kid)에 대해 만든 것인지와 캡슐 바이트. */
public record KemCiphertext(KemAlg alg, String kid, byte[] value) {

    public KemCiphertext {
        Objects.requireNonNull(alg, "alg");
        Objects.requireNonNull(kid, "kid");
        Objects.requireNonNull(value, "value");
        value = value.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KemCiphertext other
                && alg == other.alg && kid.equals(other.kid) && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(alg, kid, Arrays.hashCode(value));
    }

    @Override
    public String toString() {
        return "KemCiphertext[alg=" + alg.id() + ", kid=" + kid + ", " + value.length + " bytes]";
    }
}
