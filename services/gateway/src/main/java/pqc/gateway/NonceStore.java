package pqc.gateway;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 한 번 쓴 nonce를 기억한다(T3 재전송). */
public interface NonceStore {

    /**
     * 처음 보는 nonce면 ttl 동안 기억하고 true, 이미 있으면 false.
     * 저장소에 닿지 못하면 예외를 던진다(통과시키지 않는다).
     */
    boolean register(String key, Duration ttl);

    /** Redis SET NX EX. 여러 게이트웨이가 같은 Redis를 쓰면 함께 막힌다. */
    static NonceStore redis(StringRedisTemplate redis) {
        return (key, ttl) -> Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("nonce:" + key, "1", ttl));
    }
}
