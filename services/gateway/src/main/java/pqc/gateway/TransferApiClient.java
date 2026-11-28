package pqc.gateway;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 검증을 통과한 요청을 이체 API(F2, 8081)로 넘긴다.
 * 이체 API의 2xx·4xx JSON 응답(접수 결과, E2xxx)은 상태 코드와 본문을 그대로 돌려준다.
 * 5xx나 JSON이 아닌 응답은 내부 오류 내용이 섞일 수 있어 기업에 넘기지 않고 E9000으로 바꾼다.
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
            ResponseEntity<String> reply;
            try {
                reply = client.post()
                        .uri("/v1/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Pqc-Corporation-Id", Long.toString(transfer.corporationId()))
                        .header("X-Pqc-Crypto-Mode", transfer.mode().name())
                        .header("X-Pqc-Request-Hash", transfer.requestHash())
                        .body(transfer.transferJson())
                        .exchange((request, response) -> ResponseEntity.status(response.getStatusCode())
                                .headers(h -> h.setContentType(response.getHeaders().getContentType()))
                                .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
            } catch (RestClientException e) {
                throw new GatewayException(ResultCode.E9000, "이체 API 호출 실패", e);
            }
            return relay(reply);
        };
    }

    /** 2xx·4xx이고 JSON(application/json, application/*+json)인 응답만 그대로 돌려준다. 나머지는 E9000. */
    static ResponseEntity<String> relay(ResponseEntity<String> reply) {
        HttpStatusCode status = reply.getStatusCode();
        MediaType type = reply.getHeaders().getContentType();
        boolean json = type != null && "application".equals(type.getType())
                && ("json".equals(type.getSubtype()) || "json".equals(type.getSubtypeSuffix()));
        if (!(status.is2xxSuccessful() || status.is4xxClientError()) || !json) {
            // 본문은 로그에도 남기지 않는다(이체 내용이나 내부 오류가 섞여 있을 수 있음)
            throw new GatewayException(ResultCode.E9000, "이체 API 응답을 넘기지 않음: 상태 " + status.value() + ", 형식 " + type);
        }
        return ResponseEntity.status(status).contentType(type).body(reply.getBody());
    }
}
