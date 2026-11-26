package pqc.crypto;

/** 설정·환경 문제(키 없음, 알고리즘 미지원 등)로 암호 연산을 할 수 없을 때. */
public class CryptoException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CryptoException(String message) {
        super(message);
    }

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
