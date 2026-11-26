package pqc.crypto;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

/**
 * 환경변수에서 키를 읽는다(위협 모델 7장 2번 결정). kid가 "gw-rsa-1"이면 다음 변수를 찾는다.
 * <pre>
 * CRYPTO_KEY_GW_RSA_1_ALG      RSA | ML-DSA | ML-KEM
 * CRYPTO_KEY_GW_RSA_1_PUBLIC   공개키, X.509 DER을 base64로
 * CRYPTO_KEY_GW_RSA_1_PRIVATE  개인키, PKCS#8 DER을 base64로 (상대 공개키만 등록할 때는 없음)
 * </pre>
 * 키 값은 로그에 남기지 않는다. 오류 메시지에도 변수 이름만 적는다.
 */
public final class EnvKeyProvider implements KeyProvider {
    private final Map<String, String> env;

    public EnvKeyProvider() {
        this(System.getenv());
    }

    /** 시험에서 환경변수 대신 Map을 넘길 수 있게 한다. */
    public EnvKeyProvider(Map<String, String> env) {
        this.env = Map.copyOf(env);
    }

    static String prefix(String kid) {
        return "CRYPTO_KEY_" + kid.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_') + "_";
    }

    @Override
    public PublicKey publicKey(String kid) {
        byte[] der = decode(kid, "PUBLIC");
        try {
            return keyFactory(kid).generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("공개키 형식 오류: " + prefix(kid) + "PUBLIC", e);
        }
    }

    @Override
    public PrivateKey privateKey(String kid) {
        byte[] der = decode(kid, "PRIVATE");
        try {
            return keyFactory(kid).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new CryptoException("개인키 형식 오류: " + prefix(kid) + "PRIVATE", e);
        }
    }

    private byte[] decode(String kid, String part) {
        String name = prefix(kid) + part;
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new CryptoException("키 없음: " + name);
        }
        try {
            return Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException e) {
            throw new CryptoException("base64 형식 오류: " + name, e);
        }
    }

    private KeyFactory keyFactory(String kid) throws GeneralSecurityException {
        String name = prefix(kid) + "ALG";
        String alg = env.get(name);
        if (alg == null) {
            throw new CryptoException("키 알고리즘 없음: " + name);
        }
        return switch (alg.strip()) {
            case "RSA" -> KeyFactory.getInstance("RSA", Providers.BC); // nosemgrep: rsa-jca (LEGACY·HYBRID용 의도된 RSA)
            case "ML-DSA" -> KeyFactory.getInstance("ML-DSA", Providers.BC);
            case "ML-KEM" -> KeyFactory.getInstance("ML-KEM", Providers.BC);
            default -> throw new CryptoException("지원하지 않는 키 알고리즘: " + name);
        };
    }
}
