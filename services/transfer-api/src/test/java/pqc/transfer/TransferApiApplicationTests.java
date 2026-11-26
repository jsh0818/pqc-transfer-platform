package pqc.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

// DB가 필요하다. POSTGRES_PASSWORD가 있을 때만 실행한다(README 참고).
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@SpringBootTest
class TransferApiApplicationTests {

	@Test
	void contextLoads() {
	}

}
