import java.security.*;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;

/**
 * 진단 규칙(tools/diagnosis/rules/) 시험용 코드. 메서드 하나에 케이스 하나.
 * 메서드 이름의 case01~case28 번호가 결과 표의 번호와 같다.
 *
 * 자동 시험: tools/diagnosis/run-tests.sh
 * - ruleid 주석(뒤에 규칙 ID를 적음) 바로 아랫줄은 그 규칙에 걸려야 한다.
 * - 이 주석이 없는 줄은 어떤 규칙에도 걸리면 안 된다(안전한 케이스).
 */
public class Cases {

    static final String ALG = "RSA";

    // ---- 정답이어야 하는 것 ----

    // 01: RSA 사용 -> 탐지돼야 함
    void case01() throws Exception {
        // ruleid: rsa-jca
        KeyPairGenerator.getInstance("RSA");
    }

    // 02: 주석 속 RSA -> 안 잡아야 함
    void case02() throws Exception {
        // KeyPairGenerator.getInstance("RSA");
    }

    // 03: 글자만 출력 -> 안 잡아야 함
    void case03() {
        System.out.println("RSA");
    }

    // 04: 변수에 담은 RSA -> 탐지돼야 함
    void case04() throws Exception {
        String a = "RSA";
        // ruleid: rsa-jca
        KeyPairGenerator.getInstance(a);
    }

    // 05: 상수에 담은 RSA -> 탐지돼야 함
    void case05() throws Exception {
        // ruleid: rsa-jca
        KeyPairGenerator.getInstance(ALG);
    }

    // 06: 글자 이어붙이기 -> 탐지돼야 함
    void case06() throws Exception {
        // ruleid: rsa-jca
        KeyPairGenerator.getInstance("R" + "SA");
    }

    // ---- 놓칠 수 있는 것 ----

    // 07: 제공자 이름 추가
    void case07() throws Exception {
        // ruleid: rsa-jca
        KeyPairGenerator.getInstance("RSA", "BC");
    }

    // 08: 소문자
    void case08() throws Exception {
        // ruleid: rsa-jca
        KeyPairGenerator.getInstance("rsa");
    }

    // 09: 패키지 이름까지 전부 적음
    void case09() throws Exception {
        // ruleid: rsa-jca
        java.security.KeyPairGenerator.getInstance("RSA");
    }

    // 10: 설정 파일 값 (설정 파일에 RSA가 적혀 있다고 가정)
    void case10() throws Exception {
        String alg = System.getProperty("crypto.alg");
        // ruleid: dynamic-crypto-alg
        KeyPairGenerator.getInstance(alg);
    }

    // 11: Cipher로 RSA 암호화
    void case11() throws Exception {
        // ruleid: rsa-jca
        Cipher.getInstance("RSA/ECB/PKCS1Padding");
    }

    // 12: Signature로 RSA 서명
    void case12() throws Exception {
        // ruleid: rsa-jca
        Signature.getInstance("SHA256withRSA");
    }

    // 13: KeyFactory로 RSA 키 불러오기
    void case13() throws Exception {
        // ruleid: rsa-jca
        KeyFactory.getInstance("RSA");
    }

    // 14: RSA 서명의 다른 이름
    void case14() throws Exception {
        // ruleid: rsa-jca
        KeyPairGenerator.getInstance("RSASSA-PSS");
    }

    // 15: Bouncy Castle RSA
    void case15() {
        // ruleid: rsa-bouncycastle
        new RSAKeyPairGenerator();
    }

    // 16: 타원곡선 (양자 취약)
    void case16() throws Exception {
        // ruleid: classical-pubkey-jca
        KeyPairGenerator.getInstance("EC");
    }

    // 17: ECDSA 서명 (양자 취약)
    void case17() throws Exception {
        // ruleid: classical-pubkey-jca
        Signature.getInstance("SHA256withECDSA");
    }

    // 18: DSA (양자 취약)
    void case18() throws Exception {
        // ruleid: classical-pubkey-jca
        KeyPairGenerator.getInstance("DSA");
    }

    // 19: DH 키 교환 (양자 취약)
    void case19() throws Exception {
        // ruleid: classical-pubkey-jca
        KeyAgreement.getInstance("DH");
    }

    // 20: ECDH 키 교환 (양자 취약)
    void case20() throws Exception {
        // ruleid: classical-pubkey-jca
        KeyAgreement.getInstance("ECDH");
    }

    // 21: Ed25519 서명 (양자 취약)
    void case21() throws Exception {
        // ruleid: classical-pubkey-jca
        KeyPairGenerator.getInstance("Ed25519");
    }

    // ---- 다시 정답이어야 하는 것 ----

    // 22: 양자내성암호 -> 안 잡아야 함
    void case22() throws Exception {
        KeyPairGenerator.getInstance("ML-DSA-65");
    }

    // 23: 해시 -> 안 잡아야 함
    void case23() throws Exception {
        MessageDigest.getInstance("SHA-256");
    }

    // ---- 이체 전문 규격(v0.2) 기준 게이트웨이 코드 ----

    // 24: 전송 구간 AES-256-GCM -> 안 잡아야 함
    void case24() throws Exception {
        javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
    }

    // 25: ML-KEM 키 교환 -> 안 잡아야 함
    void case25() throws Exception {
        KeyGenerator.getInstance("ML-KEM", "BC");
    }

    // 26: ML-DSA 서명 (제공자 이름 포함) -> 안 잡아야 함
    void case26() throws Exception {
        Signature.getInstance("ML-DSA", "BC");
    }

    // 27: 요청의 alg 값으로 알고리즘 선택 -> 확인 필요(WARNING)
    void case27(String algFromRequest) throws Exception {
        // ruleid: dynamic-crypto-alg
        Signature.getInstance(algFromRequest);
    }

    // 28: HYBRID에서 일부러 쓰는 RSA, 예외 표시 -> 안 잡아야 함
    void case28() throws Exception {
        Signature.getInstance("SHA256withRSA"); // nosemgrep: rsa-jca
    }
}
