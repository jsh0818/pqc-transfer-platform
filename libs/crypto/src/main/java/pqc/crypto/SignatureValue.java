package pqc.crypto;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** 전문 규격 signatures[]의 한 항목: {alg, kid, value}. value는 전문에서 base64로 보낸다. */
public record SignatureValue(String alg, String kid, byte[] value) {

    public SignatureValue {
        Objects.requireNonNull(alg, "alg");
        Objects.requireNonNull(kid, "kid");
        Objects.requireNonNull(value, "value");
        value = value.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }

    public String valueBase64() {
        return Base64.getEncoder().encodeToString(value);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SignatureValue other
                && alg.equals(other.alg) && kid.equals(other.kid) && Arrays.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(alg, kid, Arrays.hashCode(value));
    }

    @Override
    public String toString() {
        return "SignatureValue[alg=" + alg + ", kid=" + kid + ", " + value.length + " bytes]";
    }
}
