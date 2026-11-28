package pqc.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.erdtman.jcs.JsonCanonicalizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 서명 대상 바이트. 요청 본문에서 security.signatures만 뺀 객체를 RFC 8785(JCS)로 정규화한 UTF-8 바이트다.
 * <pre>
 * JCS({"security": {crypto_mode, session_id, timestamp, nonce}, "transfer": {...}})
 * </pre>
 * 키 이름("transfer")까지 서명에 들어가므로, 같은 서명을 다른 용도의 요청(예: 상태 조회의 "inquiry")에 다시 쓸 수 없다.
 * 기업 시스템도 같은 규칙으로 만들어 서명한다.
 */
public final class SigningInput {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private SigningInput() {
    }

    /** @param envelope security, transfer를 가진 요청 본문(signatures는 있어도 되고 없어도 된다) */
    public static byte[] of(JsonNode envelope) {
        ObjectNode copy = (ObjectNode) envelope.deepCopy();
        JsonNode security = copy.get("security");
        if (security instanceof ObjectNode s) {
            s.remove("signatures");
        }
        try {
            return new JsonCanonicalizer(JSON.writeValueAsString(copy)).getEncodedString()
                    .getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GatewayException(ResultCode.E1000, "JCS 정규화 실패", e);
        }
    }
}
