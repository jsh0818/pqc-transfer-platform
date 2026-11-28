package pqc.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pqc.crypto.CryptoMode;

class TransferApiClientTest {

    MockRestServiceServer server;
    TransferApiClient client;
    final VerifiedTransfer transfer = new VerifiedTransfer(1001, CryptoMode.HYBRID,
            "{\"amount\":\"1500000\"}", "ab".repeat(32));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://transfer-api:8081");
        server = MockRestServiceServer.bindTo(builder).build();
        client = TransferApiClient.rest(builder.build());
    }

    @Test
    void 확인된_기업_모드_해시를_헤더로_transfer를_본문으로_넘긴다() {
        server.expect(requestTo("http://transfer-api:8081/v1/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Pqc-Corporation-Id", "1001"))
                .andExpect(header("X-Pqc-Crypto-Mode", "HYBRID"))
                .andExpect(header("X-Pqc-Request-Hash", "ab".repeat(32)))
                .andExpect(content().json("{\"amount\":\"1500000\"}", JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"result_code\":\"0000\"}"));

        var response = client.forward(transfer);
        assertEquals(201, response.getStatusCode().value());
        assertEquals("{\"result_code\":\"0000\"}", response.getBody());
        server.verify();
    }

    @Test
    void 이체_API의_4xx_JSON은_예외가_아니라_그대로_돌려준다() {
        server.expect(requestTo("http://transfer-api:8081/v1/transfers"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"result_code\":\"E2007\"}"));
        var response = client.forward(transfer);
        assertEquals(409, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertEquals("{\"result_code\":\"E2007\"}", response.getBody());
    }

    @Test
    void problem_json처럼_json으로_끝나는_형식도_그대로_돌려준다() {
        server.expect(requestTo("http://transfer-api:8081/v1/transfers"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"result_code\":\"E2002\"}"));
        var response = client.forward(transfer);
        assertEquals(422, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
    }

    /** 5xx, 3xx, JSON이 아닌 응답, 형식 없는 응답은 내부 내용이 섞일 수 있어 넘기지 않는다. */
    @ParameterizedTest
    @CsvSource({
            "500, application/json",
            "503, application/json",
            "302, application/json",
            "400, text/html",
            "201, text/plain",
            "201, ''",
    })
    void 넘기면_안_되는_응답은_E9000(int status, String type) {
        var response = withStatus(HttpStatusCode.valueOf(status)).body("{\"trace\":\"at pqc.transfer...\"}");
        server.expect(requestTo("http://transfer-api:8081/v1/transfers"))
                .andRespond(type.isEmpty() ? response : response.contentType(MediaType.parseMediaType(type)));
        GatewayException e = assertThrows(GatewayException.class, () -> client.forward(transfer));
        assertEquals(ResultCode.E9000, e.code());
        assertFalse(e.getMessage().contains("trace"), e.getMessage());
    }

    @Test
    void 연결_실패는_E9000() {
        server.expect(requestTo("http://transfer-api:8081/v1/transfers"))
                .andRespond(withException(new IOException("connection refused")));
        assertEquals(ResultCode.E9000, assertThrows(GatewayException.class, () -> client.forward(transfer)).code());
    }
}
