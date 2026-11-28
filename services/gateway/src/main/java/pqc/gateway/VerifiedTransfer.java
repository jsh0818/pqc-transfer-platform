package pqc.gateway;

import pqc.crypto.CryptoMode;

/**
 * 검증을 통과한 이체 요청. 이체 API로 넘길 내용이다.
 *
 * @param corporationId 서명으로 확인된 기업
 * @param mode          요청에 쓴 암호 모드(transfer_request.crypto_mode_used)
 * @param transferJson  transfer 객체(JSON)
 * @param requestHash   서명 대상 바이트의 SHA-256(hex). transfer_request.request_hash
 */
public record VerifiedTransfer(long corporationId, CryptoMode mode, String transferJson, String requestHash) {
}
