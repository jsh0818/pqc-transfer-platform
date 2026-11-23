import java.security.KeyPairGenerator;
import java.security.MessageDigest;

public class Sample {
    void unsafe() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        kpg.generateKeyPair();
    }

    void safe() throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.digest("hello".getBytes());
    }
}
