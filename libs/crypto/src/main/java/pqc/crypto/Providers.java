package pqc.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Provider;
import java.security.SecureRandom;

/** Bouncy Castle 제공자와 난수 생성기. JVM 전역 등록(Security.addProvider) 없이 객체를 직접 넘긴다. */
final class Providers {
    static final Provider BC = new BouncyCastleProvider();
    static final SecureRandom RANDOM = new SecureRandom();

    private Providers() {
    }
}
