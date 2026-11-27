package pqc.gateway;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** POST /v1/transfers: 검증을 통과하면 이체 API로 넘긴다. */
@RestController
public class TransferController {

    /** HYBRID 서명 2개(약 4.8KB)와 이체 내용이 충분히 들어가는 크기. 큰 본문으로 메모리와 서명 검증 부하를 만드는 것을 막는다. */
    static final int MAX_BODY_BYTES = 32 * 1024;

    private final RequestVerifier verifier;
    private final TransferApiClient transferApi;

    public TransferController(RequestVerifier verifier, TransferApiClient transferApi) {
        this.verifier = verifier;
        this.transferApi = transferApi;
    }

    @PostMapping(path = "/v1/transfers", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(HttpServletRequest request) throws IOException {
        VerifiedTransfer verified = verifier.verify(readBody(request));
        return transferApi.forward(verified);
    }

    /** 한도까지만 읽는다. 본문 전체를 메모리에 올린 뒤에 크기를 보면 큰 본문을 막지 못한다. */
    static String readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw new GatewayException(ResultCode.E1000, "본문 크기 초과: Content-Length " + request.getContentLengthLong());
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1); // chunked처럼 길이를 미리 알 수 없는 경우
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new GatewayException(ResultCode.E1000, "본문 크기 초과");
        }
        return new String(body, StandardCharsets.UTF_8); // 빈 본문은 RequestVerifier가 E1000으로 거부한다
    }
}
