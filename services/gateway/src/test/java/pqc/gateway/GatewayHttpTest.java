package pqc.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import pqc.crypto.CryptoMode;
import pqc.crypto.KeyProvider;

/** HTTP 상태, 외부 메시지, 이체 API 전달을 본다. Redis와 이체 API는 시험용으로 바꾼다. */
@SpringBootTest(properties = {
        "spring.data.redis.password=unused-in-test",
        "gateway.corporations.1001.floor=LEGACY",
        "gateway.corporations.1001.kids=acme-rsa,acme-mldsa",
        "gateway.corporations.1002.floor=HYBRID",
        "gateway.corporations.1002.kids=beta-rsa,beta-mldsa",
})
@AutoConfigureMockMvc
class GatewayHttpTest {

    static final AtomicReference<VerifiedTransfer> forwarded = new AtomicReference<>();
    static final AtomicReference<ResponseEntity<String>> apiResponse = new AtomicReference<>();

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        KeyProvider testKeys() {
            return Fixtures.KEYS;
        }

        @Bean
        @Primary
        Clock testClock() {
            return Fixtures.CLOCK;
        }

        @Bean
        @Primary
        NonceStore testNonces() {
            return new Fixtures.MemoryNonceStore();
        }

        @Bean
        @Primary
        TransferApiClient testTransferApi() {
            return t -> {
                forwarded.set(t);
                ResponseEntity<String> r = apiResponse.get();
                if (r == null) {
                    throw new GatewayException(ResultCode.E9000, "이체 API 호출 실패");
                }
                return r;
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    org.springframework.core.env.Environment env;

    @Test
    void Redis와_연결_응답_대기는_2초() {
        // 기본값(60초)이면 Redis가 멈췄을 때 요청마다 60초씩 묶인다
        assertEquals(java.time.Duration.ofSeconds(2), env.getProperty("spring.data.redis.timeout", java.time.Duration.class));
        assertEquals(java.time.Duration.ofSeconds(2), env.getProperty("spring.data.redis.connect-timeout", java.time.Duration.class));
    }

    @BeforeEach
    void reset() {
        forwarded.set(null);
        apiResponse.set(ResponseEntity.status(201).contentType(MediaType.APPLICATION_JSON)
                .body("{\"result_code\":\"0000\",\"transfer_id\":52001,\"status\":\"REQUESTED\"}"));
    }

    @Test
    void 정상_요청은_이체_API로_넘기고_응답을_그대로_돌려준다() throws Exception {
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(Fixtures.signed(CryptoMode.HYBRID).toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result_code").value("0000"))
                .andExpect(jsonPath("$.transfer_id").value(52001));
        assertEquals(1001, forwarded.get().corporationId());
        assertEquals(CryptoMode.HYBRID, forwarded.get().mode());
    }

    @Test
    void 이체_API의_거부_응답도_그대로_돌려준다() throws Exception {
        apiResponse.set(ResponseEntity.status(422).contentType(MediaType.APPLICATION_JSON)
                .body("{\"result_code\":\"E2002\",\"message\":\"잔액이 부족합니다\"}"));
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(Fixtures.signed(CryptoMode.LEGACY).toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.result_code").value("E2002"));
    }

    @Test
    void 서명_실패는_401_E1001이고_원인을_알려주지_않는다() throws Exception {
        var envelope = Fixtures.signed(CryptoMode.LEGACY);
        Fixtures.transfer(envelope).put("amount", "9999999");
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(envelope.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"result_code\":\"E1001\",\"message\":\"요청을 확인할 수 없습니다\"}", JsonCompareMode.STRICT));
        assertNull(forwarded.get(), "거부된 요청은 이체 API로 가지 않는다");
    }

    @Test
    void 재전송은_401_E1002() throws Exception {
        String body = Fixtures.signed(CryptoMode.LEGACY).toString();
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"result_code\":\"E1002\",\"message\":\"요청을 확인할 수 없습니다\"}", JsonCompareMode.STRICT));
    }

    @Test
    void 최저_모드_미만은_403_E1003() throws Exception {
        String body = Fixtures.sign(Fixtures.unsigned(CryptoMode.LEGACY, 1002), Fixtures.betaKids()).toString();
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"result_code\":\"E1003\",\"message\":\"요청을 확인할 수 없습니다\"}", JsonCompareMode.STRICT));
    }

    @Test
    void 형식_오류는_400_E1000() throws Exception {
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).content("{bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result_code").value("E1000"));
    }

    @Test
    void JSON이_아닌_Content_Type은_400_E1000() throws Exception {
        mvc.perform(post("/v1/transfers").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result_code").value("E1000"));
    }

    @Test
    void 본문이_32KB를_넘으면_서명이_맞아도_400_E1000() throws Exception {
        // 올바르게 서명된 요청 뒤에 공백만 붙였다(JSON으로는 같은 요청). 크기 제한이 없으면 통과했을 요청이다
        String signed = Fixtures.signed(CryptoMode.LEGACY).toString();
        String big = signed + " ".repeat(TransferController.MAX_BODY_BYTES);
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result_code").value("E1000"));
        assertNull(forwarded.get());
    }

    @Test
    void 없는_경로는_404_허용되지_않은_메서드는_405() throws Exception {
        mvc.perform(post("/nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.result_code").value("E1000"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/transfers"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.result_code").value("E1000"));
    }

    @Test
    void 빈_본문은_400_E1000() throws Exception {
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result_code").value("E1000"));
    }

    @Test
    void 공백을_붙여도_32KB_이하면_통과() throws Exception {
        String signed = Fixtures.signed(CryptoMode.LEGACY).toString();
        int bytes = signed.getBytes(java.nio.charset.StandardCharsets.UTF_8).length; // 한글 메모는 글자당 3바이트
        String padded = signed + " ".repeat(TransferController.MAX_BODY_BYTES - bytes);
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(padded))
                .andExpect(status().isCreated());
    }

    @Test
    void 이체_API에_닿지_못하면_500_E9000() throws Exception {
        apiResponse.set(null);
        mvc.perform(post("/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(Fixtures.signed(CryptoMode.PQC).toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().json("{\"result_code\":\"E9000\",\"message\":\"서버 내부 오류입니다\"}", JsonCompareMode.STRICT));
    }
}
