package pqc.crypto;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 세션 키를 만드는 키 교환(ADR-0001).
 * 보내는 쪽(기업 시스템)은 받는 쪽(게이트웨이)의 공개키로 캡슐을 만들고, 받는 쪽은 개인키로 같은 세션 키를 복원한다.
 * HYBRID는 RSA와 ML-KEM 비밀을 이어 붙여 HKDF로 키를 만들므로, 한쪽이 깨져도 다른 쪽을 모르면 키를 알 수 없다.
 */
public final class KeyExchange {
    static final int SECRET_BYTES = 32;
    static final int SESSION_KEY_BYTES = 32;
    private static final byte[] LABEL = "pqc-transfer/v1".getBytes(StandardCharsets.US_ASCII);
    private static final OAEPParameterSpec OAEP_SHA256 =
            new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private final KeyProvider keys;

    public KeyExchange(KeyProvider keys) {
        this.keys = keys;
    }

    /** 보내는 쪽의 결과: 상대에게 보낼 캡슐들과, 내가 쓸 세션 키. */
    public record Initiated(CryptoMode mode, List<KemCiphertext> ciphertexts, SecretKey sessionKey) {
        public Initiated {
            ciphertexts = List.copyOf(ciphertexts);
        }
    }

    /**
     * 보내는 쪽: 상대 공개키(kid)로 모드에 맞는 캡슐을 만든다.
     *
     * @param peerKids 알고리즘별 상대 공개키의 kid. 모드가 요구하는 알고리즘이 모두 있어야 한다.
     */
    public Initiated initiate(CryptoMode mode, Map<KemAlg, String> peerKids) {
        List<KemCiphertext> ciphertexts = new ArrayList<>();
        Map<KemAlg, byte[]> secrets = new EnumMap<>(KemAlg.class);
        try {
            for (KemAlg alg : mode.requiredKemAlgs()) {
                String kid = peerKids.get(alg);
                if (kid == null) {
                    throw new CryptoException(mode + " 키 교환에 필요한 kid 없음: " + alg.id());
                }
                PublicKey peer = keys.publicKey(kid);
                byte[] secret;
                byte[] encapsulation;
                switch (alg) {
                    case RSA_OAEP -> {
                        secret = new byte[SECRET_BYTES];
                        Providers.RANDOM.nextBytes(secret);
                        encapsulation = rsaOaep(Cipher.ENCRYPT_MODE, peer).doFinal(secret);
                    }
                    case ML_KEM -> {
                        KeyGenerator kg = KeyGenerator.getInstance("ML-KEM", Providers.BC);
                        kg.init(new KEMGenerateSpec(peer, "AES", SECRET_BYTES * 8), Providers.RANDOM);
                        SecretKeyWithEncapsulation enc = (SecretKeyWithEncapsulation) kg.generateKey();
                        secret = enc.getEncoded();
                        encapsulation = enc.getEncapsulation();
                    }
                    default -> throw new IllegalStateException(alg.toString());
                }
                secrets.put(alg, secret);
                ciphertexts.add(new KemCiphertext(alg, kid, encapsulation));
            }
            return new Initiated(mode, ciphertexts, deriveSessionKey(mode, ciphertexts, secrets));
        } catch (GeneralSecurityException e) {
            throw new CryptoException(mode + " 캡슐 생성 실패", e);
        } finally {
            secrets.values().forEach(s -> Arrays.fill(s, (byte) 0));
        }
    }

    /**
     * 받는 쪽: 캡슐 집합이 모드가 요구하는 것과 정확히 같아야 하고, 내 개인키(kid)로 비밀을 복원해 세션 키를 만든다.
     * 하나라도 어긋나면 VerificationException.
     */
    public SecretKey accept(CryptoMode mode, List<KemCiphertext> ciphertexts) {
        if (ciphertexts == null || ciphertexts.isEmpty()) {
            throw new VerificationException("캡슐 없음");
        }
        Map<KemAlg, KemCiphertext> byAlg = new EnumMap<>(KemAlg.class);
        for (KemCiphertext ct : ciphertexts) {
            if (byAlg.put(ct.alg(), ct) != null) {
                throw new VerificationException("같은 알고리즘의 캡슐이 두 번 있음: " + ct.alg().id());
            }
        }
        if (!byAlg.keySet().equals(mode.requiredKemAlgs())) {
            throw new VerificationException(mode + " 모드의 캡슐 조합이 아님: " + byAlg.keySet());
        }
        Map<KemAlg, byte[]> secrets = new EnumMap<>(KemAlg.class);
        try {
            for (KemCiphertext ct : byAlg.values()) {
                PrivateKey own;
                try {
                    own = keys.privateKey(ct.kid());
                } catch (CryptoException e) {
                    throw new VerificationException("등록되지 않은 kid: " + ct.kid(), e);
                }
                try {
                    secrets.put(ct.alg(), switch (ct.alg()) {
                        case RSA_OAEP -> rsaOaep(Cipher.DECRYPT_MODE, own).doFinal(ct.value());
                        case ML_KEM -> {
                            KeyGenerator kg = KeyGenerator.getInstance("ML-KEM", Providers.BC);
                            kg.init(new KEMExtractSpec(own, ct.value(), "AES", SECRET_BYTES * 8));
                            yield kg.generateKey().getEncoded();
                        }
                    });
                } catch (GeneralSecurityException | IllegalArgumentException e) {
                    throw new VerificationException(ct.alg().id() + " 캡슐 복원 실패: kid=" + ct.kid(), e);
                }
            }
            if (secrets.get(KemAlg.RSA_OAEP) != null && secrets.get(KemAlg.RSA_OAEP).length != SECRET_BYTES) {
                throw new VerificationException("RSA-OAEP 비밀 길이 오류");
            }
            return deriveSessionKey(mode, new ArrayList<>(byAlg.values()), secrets);
        } finally {
            secrets.values().forEach(s -> Arrays.fill(s, (byte) 0));
        }
    }

    /**
     * HKDF-SHA256으로 세션 키를 만든다.
     * IKM = 알고리즘 순서(RSA_OAEP, ML_KEM)대로 이어 붙인 비밀,
     * info = "pqc-transfer/v1" + 모드 이름 + 각 캡슐(알고리즘, kid, 바이트). 모든 항목 앞에 길이를 붙여 경계를 분명히 한다.
     */
    static SecretKey deriveSessionKey(CryptoMode mode, List<KemCiphertext> ciphertexts, Map<KemAlg, byte[]> secrets) {
        List<KemCiphertext> ordered = new ArrayList<>(ciphertexts);
        ordered.sort(Comparator.comparing(KemCiphertext::alg));

        ByteArrayOutputStream ikm = new ByteArrayOutputStream();
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        writeField(info, LABEL);
        writeField(info, mode.name().getBytes(StandardCharsets.US_ASCII));
        for (KemCiphertext ct : ordered) {
            byte[] secret = secrets.get(ct.alg());
            if (secret == null) {
                throw new CryptoException("비밀 없음: " + ct.alg().id());
            }
            ikm.writeBytes(secret);
            writeField(info, ct.alg().id().getBytes(StandardCharsets.US_ASCII));
            writeField(info, ct.kid().getBytes(StandardCharsets.UTF_8));
            writeField(info, ct.value());
        }

        byte[] ikmBytes = ikm.toByteArray();
        byte[] key = new byte[SESSION_KEY_BYTES];
        try {
            HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
            hkdf.init(new HKDFParameters(ikmBytes, null, info.toByteArray()));
            hkdf.generateBytes(key, 0, key.length);
            return new SecretKeySpec(key, "AES");
        } finally {
            Arrays.fill(ikmBytes, (byte) 0);
            Arrays.fill(key, (byte) 0);
        }
    }

    private static void writeField(ByteArrayOutputStream out, byte[] field) {
        out.writeBytes(ByteBuffer.allocate(4).putInt(field.length).array());
        out.writeBytes(field);
    }

    private static Cipher rsaOaep(int opmode, java.security.Key key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding", Providers.BC); // nosemgrep: rsa-jca (LEGACY·HYBRID용 의도된 RSA)
        cipher.init(opmode, key, OAEP_SHA256, Providers.RANDOM);
        return cipher;
    }
}
