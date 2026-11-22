import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.KeyGenerator;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Arrays;

/**
 * Bouncy Castle ML-KEM / ML-DSA 지원 확인용 최소 예제.
 * 실행: java -cp bcprov-jdk18on-1.87.jar BcPqcCheck.java
 */
public class BcPqcCheck {

    static int passed = 0, failed = 0;

    public static void main(String[] args) {
        Security.addProvider(new BouncyCastleProvider());
        System.out.println("Java " + System.getProperty("java.version")
                + " / BC provider " + Security.getProvider("BC").getVersionStr());
        System.out.println("-".repeat(60));

        for (String p : new String[]{"ML-KEM-512", "ML-KEM-768", "ML-KEM-1024"}) checkKem(p);
        for (String p : new String[]{"ML-DSA-44", "ML-DSA-65", "ML-DSA-87"}) checkDsa(p);

        System.out.println("-".repeat(60));
        System.out.println("결과: PASS " + passed + " / FAIL " + failed);
        System.out.println(failed == 0
                ? "=> ML-KEM, ML-DSA 모두 지원됨. 프로젝트 핵심 기술 사용 가능."
                : "=> 일부 실패. BC 버전이 1.79 이상인지 확인하세요.");
    }

    /** 키 쌍 생성 -> 캡슐화(보내는 쪽) -> 디캡슐화(받는 쪽) -> 공유 비밀 일치 확인 */
    static void checkKem(String alg) {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(alg, "BC");
            long t0 = System.nanoTime();
            KeyPair kp = kpg.generateKeyPair();
            long tKeygen = System.nanoTime() - t0;

            // 보내는 쪽: 상대 공개키로 공유 비밀 + 캡슐 생성
            KeyGenerator sender = KeyGenerator.getInstance("ML-KEM", "BC");
            sender.init(new KEMGenerateSpec(kp.getPublic(), "AES"), new SecureRandom());
            long t1 = System.nanoTime();
            SecretKeyWithEncapsulation enc = (SecretKeyWithEncapsulation) sender.generateKey();
            long tEncap = System.nanoTime() - t1;

            // 받는 쪽: 개인키 + 캡슐로 같은 공유 비밀 복원
            KeyGenerator receiver = KeyGenerator.getInstance("ML-KEM", "BC");
            receiver.init(new KEMExtractSpec(kp.getPrivate(), enc.getEncapsulation(), "AES"));
            long t2 = System.nanoTime();
            SecretKeyWithEncapsulation dec = (SecretKeyWithEncapsulation) receiver.generateKey();
            long tDecap = System.nanoTime() - t2;

            boolean same = Arrays.equals(enc.getEncoded(), dec.getEncoded());
            report(alg, same, String.format("공개키 %dB, 캡슐 %dB, 공유비밀 %dB | keygen %.2fms encap %.2fms decap %.2fms | 비밀 일치=%s",
                    kp.getPublic().getEncoded().length, enc.getEncapsulation().length, enc.getEncoded().length,
                    tKeygen / 1e6, tEncap / 1e6, tDecap / 1e6, same));
        } catch (Throwable e) {
            report(alg, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 키 쌍 생성 -> 서명 -> 검증(정상) -> 메시지 변조 후 검증(실패해야 정상) */
    static void checkDsa(String alg) {
        try {
            KeyPair kp = KeyPairGenerator.getInstance(alg, "BC").generateKeyPair();
            byte[] msg = "transfer: A -> B 10000 KRW".getBytes(StandardCharsets.UTF_8);

            Signature signer = Signature.getInstance("ML-DSA", "BC");
            signer.initSign(kp.getPrivate());
            signer.update(msg);
            long t0 = System.nanoTime();
            byte[] sig = signer.sign();
            long tSign = System.nanoTime() - t0;

            Signature verifier = Signature.getInstance("ML-DSA", "BC");
            verifier.initVerify(kp.getPublic());
            verifier.update(msg);
            long t1 = System.nanoTime();
            boolean ok = verifier.verify(sig);
            long tVerify = System.nanoTime() - t1;

            byte[] tampered = msg.clone();
            tampered[0] ^= 1;
            verifier.initVerify(kp.getPublic());
            verifier.update(tampered);
            boolean tamperedOk = verifier.verify(sig);

            boolean pass = ok && !tamperedOk;
            report(alg, pass, String.format("공개키 %dB, 서명 %dB | sign %.2fms verify %.2fms | 정상검증=%s 변조검증=%s(false여야 정상)",
                    kp.getPublic().getEncoded().length, sig.length, tSign / 1e6, tVerify / 1e6, ok, tamperedOk));
        } catch (Throwable e) {
            report(alg, false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static void report(String alg, boolean ok, String detail) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + alg + "  " + detail);
    }
}
