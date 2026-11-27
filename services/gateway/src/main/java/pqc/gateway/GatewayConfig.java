package pqc.gateway;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import pqc.crypto.EnvKeyProvider;
import pqc.crypto.KeyProvider;
import pqc.crypto.Signer;

@Configuration
public class GatewayConfig {

    /** 기업 공개키는 환경변수 CRYPTO_KEY_&lt;KID&gt;_ALG, _PUBLIC으로 넣는다(libs/crypto EnvKeyProvider, T9). */
    @Bean
    KeyProvider keyProvider() {
        return new EnvKeyProvider();
    }

    @Bean
    Signer signer(KeyProvider keys) {
        return new Signer(keys);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CorporationDirectory corporationDirectory(GatewayProperties properties) {
        return CorporationDirectory.of(properties);
    }

    @Bean
    NonceStore nonceStore(StringRedisTemplate redis) {
        return NonceStore.redis(redis);
    }

    /** 이체 API가 멈춰도 게이트웨이 요청이 무한정 쌓이지 않도록 연결·응답 시간을 제한한다(T12). */
    @Bean
    TransferApiClient transferApiClient(GatewayProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return TransferApiClient.rest(RestClient.builder()
                .baseUrl(properties.transferApiUrl().toString())
                .requestFactory(factory)
                .build());
    }

    @Bean
    RequestVerifier requestVerifier(CorporationDirectory corporations, Signer signer, NonceStore nonces, Clock clock,
            GatewayProperties properties) {
        return new RequestVerifier(corporations, signer, nonces, clock, properties.clockSkew(), properties.nonceTtl());
    }
}
