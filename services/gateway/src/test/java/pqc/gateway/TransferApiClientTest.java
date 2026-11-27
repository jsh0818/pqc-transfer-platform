package pqc.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
    void 이체_API의_4xx_5xx는_예외가_아니라_그대로_돌려준다() {
        server.expect(requestTo("http://transfer-api:8081/v1/transfers"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"result_code\":\"E2007\"}"));
        var response = client.forward(transfer);
        assertEquals(409, response.getStatusCode().value());
        assertEquals("{\"result_code\":\"E2007\"}", response.getBody());
    }

    @Test
    void 연결_실패는_E9000() {
        server.expect(requestTo("http://transfer-api:8081/v1/transfers"))
                .andRespond(withException(new IOException("connection refused")));
        assertEquals(ResultCode.E9000, assertThrows(GatewayException.class, () -> client.forward(transfer)).code());
    }
}
