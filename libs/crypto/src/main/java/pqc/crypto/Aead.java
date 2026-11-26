package pqc.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * 본문 암호화: AES-256-GCM. 출력 형식은 IV(12바이트) || 암호문(16바이트 태그 포함).
 * IV는 암호화할 때마다 난수로 새로 만든다. 같은 키에서 IV가 겹치면 GCM의 안전성이 깨진다.
 */
public final class Aead {
    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;

    private Aead() {
    }

    /** @param aad 암호화하지 않지만 변조를 막을 데이터(예: session_id). 없으면 빈 배열. */
    public static byte[] encrypt(SecretKey key, byte[] plaintext, byte[] aad) {
        checkKey(key);
        byte[] iv = new byte[IV_BYTES];
        Providers.RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad);
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = Arrays.copyOf(iv, IV_BYTES + ciphertext.length);
            System.arraycopy(ciphertext, 0, out, IV_BYTES, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-GCM 암호화 실패", e);
        }
    }

    /** 태그가 맞지 않으면(본문·AAD 변조, 다른 키) VerificationException. */
    public static byte[] decrypt(SecretKey key, byte[] ivAndCiphertext, byte[] aad) {
        checkKey(key);
        if (ivAndCiphertext == null || ivAndCiphertext.length < IV_BYTES + TAG_BITS / 8) {
            throw new VerificationException("암호문이 너무 짧음");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, ivAndCiphertext, 0, IV_BYTES));
            cipher.updateAAD(aad);
            return cipher.doFinal(ivAndCiphertext, IV_BYTES, ivAndCiphertext.length - IV_BYTES);
        } catch (AEADBadTagException e) {
            throw new VerificationException("복호화 실패(태그 불일치)", e);
        } catch (GeneralSecurityException e) {
            throw new CryptoException("AES-GCM 복호화 오류", e);
        }
    }

    private static void checkKey(SecretKey key) {
        byte[] encoded = key.getEncoded();
        try {
            if (!"AES".equals(key.getAlgorithm()) || encoded == null || encoded.length != 32) {
                throw new CryptoException("AES-256 키가 아님");
            }
        } finally {
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }
}
