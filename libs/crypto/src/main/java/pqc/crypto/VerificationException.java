package pqc.crypto;

/**
 * 요청이 검증을 통과하지 못했을 때(서명 불일치, 알고리즘 조합 불일치, 복호화 실패).
 * 게이트웨이는 이 예외를 E1001 등으로 바꾸고, 자세한 원인은 감사 로그에만 남긴다.
 */
public class VerificationException extends CryptoException {
    private static final long serialVersionUID = 1L;

    public VerificationException(String message) {
        super(message);
    }

    public VerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
