package pqc.crypto;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * 키를 kid(키 번호)로 찾는다. 키는 코드 밖에서 주입한다(위협 T9).
 * 키를 교체할 때는 새 kid로 등록하고, 상대가 새 kid를 쓰기 시작하면 옛 kid를 지운다.
 */
public interface KeyProvider {

    /** 공개키. 없으면 CryptoException. */
    PublicKey publicKey(String kid);

    /** 개인키. 없으면 CryptoException. */
    PrivateKey privateKey(String kid);
}
