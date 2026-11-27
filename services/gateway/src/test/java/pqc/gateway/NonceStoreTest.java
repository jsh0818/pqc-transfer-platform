package pqc.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class NonceStoreTest {

    @SuppressWarnings("unchecked")
    @Test
    void Redis_SET_NX에_TTL을_붙여_처음일_때만_true() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        Duration ttl = Duration.ofMinutes(10);
        when(ops.setIfAbsent("nonce:1001:a", "1", ttl)).thenReturn(true);
        when(ops.setIfAbsent("nonce:1001:b", "1", ttl)).thenReturn(false);
        when(ops.setIfAbsent("nonce:1001:c", "1", ttl)).thenReturn(null); // 파이프라인·트랜잭션 중이면 null

        NonceStore store = NonceStore.redis(redis);
        assertTrue(store.register("1001:a", ttl));
        assertFalse(store.register("1001:b", ttl));
        assertFalse(store.register("1001:c", ttl));
        verify(ops).setIfAbsent("nonce:1001:a", "1", ttl);
    }
}
