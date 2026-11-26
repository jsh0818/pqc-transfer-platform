package pqc.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AeadTest {
    private static final byte[] BODY = "{\"amount\":\"1500000\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD = "session-1".getBytes(StandardCharsets.UTF_8);

    private static SecretKey key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return new SecretKeySpec(k, "AES");
    }

    @Test
    void 암호화_복호화() {
        byte[] out = Aead.encrypt(key(1), BODY, AAD);
        assertEquals(Aead.IV_BYTES + BODY.length + Aead.TAG_BITS / 8, out.length);
        assertArrayEquals(BODY, Aead.decrypt(key(1), out, AAD));
    }

    @Test
    void IV는_매번_다름() {
        Set<String> ivs = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ivs.add(Arrays.toString(Arrays.copyOf(Aead.encrypt(key(1), BODY, AAD), Aead.IV_BYTES)));
        }
        assertEquals(1000, ivs.size());
    }

    @Test
    void 암호문_어느_위치든_1비트_변조시_실패_T5() {
        byte[] out = Aead.encrypt(key(1), BODY, AAD);
        for (int i = 0; i < out.length; i++) {
            byte[] tampered = TestKeys.flipBit(out, i);
            assertThrows(VerificationException.class, () -> Aead.decrypt(key(1), tampered, AAD));
        }
    }

    @Test
    void AAD가_다르면_실패() {
        byte[] out = Aead.encrypt(key(1), BODY, AAD);
        assertThrows(VerificationException.class,
                () -> Aead.decrypt(key(1), out, "session-2".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 키가_다르면_실패() {
        byte[] out = Aead.encrypt(key(1), BODY, AAD);
        assertThrows(VerificationException.class, () -> Aead.decrypt(key(2), out, AAD));
    }

    @Test
    void 너무_짧은_입력은_실패() {
        assertThrows(VerificationException.class, () -> Aead.decrypt(key(1), new byte[27], AAD));
        assertThrows(VerificationException.class, () -> Aead.decrypt(key(1), null, AAD));
    }

    @Test
    void AES_256이_아닌_키는_거부() {
        assertThrows(CryptoException.class, () -> Aead.encrypt(new SecretKeySpec(new byte[16], "AES"), BODY, AAD));
    }
}
