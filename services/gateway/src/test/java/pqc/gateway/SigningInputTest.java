package pqc.gateway;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SigningInputTest {

    @Test
    void 키는_정렬되고_공백은_없고_signatures는_빠진다() throws Exception {
        String body = """
                { "transfer": { "memo": "11월", "amount": "1500", "corporation_id": 1001 },
                  "security": { "nonce": "n", "signatures": [ {"alg": "RSA"} ], "crypto_mode": "LEGACY" } }
                """;
        String expected = "{\"security\":{\"crypto_mode\":\"LEGACY\",\"nonce\":\"n\"},"
                + "\"transfer\":{\"amount\":\"1500\",\"corporation_id\":1001,\"memo\":\"11월\"}}";
        assertEquals(expected, new String(SigningInput.of(Fixtures.JSON.readTree(body)), StandardCharsets.UTF_8));
    }

    @Test
    void 원본은_바꾸지_않는다() throws Exception {
        var node = Fixtures.JSON.readTree("{\"security\":{\"signatures\":[]},\"transfer\":{}}");
        SigningInput.of(node);
        assertEquals(true, node.get("security").has("signatures"));
    }

    @Test
    void RFC_8785_예시처럼_문자열과_숫자를_정규화한다() throws Exception {
        // RFC 8785 3.2.2: 숫자는 ECMAScript 방식, 문자열은 필요한 것만 이스케이프
        String body = "{\"b\":1E2,\"a\":\"\\u0041\\u00e9\\n\"}";
        assertArrayEquals("{\"a\":\"Aé\\n\",\"b\":100}".getBytes(StandardCharsets.UTF_8),
                SigningInput.of(Fixtures.JSON.readTree(body)));
    }
}
