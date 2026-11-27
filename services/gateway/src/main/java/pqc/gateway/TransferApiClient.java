package pqc.gateway;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 검증을 통과한 요청을 이체 API(F2, 8081)로 넘긴다. 이체 API의 응답(상태 코드와 본문)은 그대로 돌려준다.
 *
 * 넘기는 형식(이체 API와 맞출 것):
 * <pre>
 * POST {transferApiUrl}/v1/transfers
 * X-Pqc-Corporation-Id: 서명으로 확인된 기업 ID
 * X-Pqc-Crypto-Mode:    LEGACY | HYBRID | PQC
 * X-Pqc-Request-Hash:   서명 대상 바이트의 SHA-256(hex)
 * 본문: transfer 객체
 * </pre>
 */
public interface TransferApiClient {

    ResponseEntity<String> forward(VerifiedTransfer transfer);

    static TransferApiClient rest(RestClient client) {
        return transfer -> {
            try {
                return client.post()
                        .uri("/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Pqc-Corporation-Id", Long.toString(transfer.corporationId()))
                        .header("X-Pqc-Crypto-Mode", transfer.mode().name())
                        .header("X-Pqc-Request-Hash", transfer.requestHash())
                        .body(transfer.transferJson())
                        .exchange((request, response) -> {
                            HttpStatusCode status = response.getStatusCode();
                            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
                        });
            } catch (RestClientException e) {
                throw new GatewayException(ResultCode.E9000, "이체 API 호출 실패", e);
            }
        };
    }
}
