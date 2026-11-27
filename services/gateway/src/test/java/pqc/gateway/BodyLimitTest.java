package pqc.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** 본문 크기 제한이 본문을 다 읽기 전에 동작하는지 본다. */
class BodyLimitTest {

    /** 끝없이 공백을 주고, 몇 바이트를 읽었는지 센다. */
    static final class EndlessStream extends ServletInputStream {
        final AtomicLong read = new AtomicLong();

        @Override
        public int read() {
            read.incrementAndGet();
            return ' ';
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
        }
    }

    static MockHttpServletRequest request(EndlessStream stream, long contentLength) {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public ServletInputStream getInputStream() {
                return stream;
            }

            @Override
            public long getContentLengthLong() {
                return contentLength;
            }
        };
        return request;
    }

    @Test
    void 길이를_모르는_큰_본문은_한도_1바이트까지만_읽고_거부() {
        EndlessStream stream = new EndlessStream();
        GatewayException e = assertThrows(GatewayException.class,
                () -> TransferController.readBody(request(stream, -1)));
        assertEquals(ResultCode.E1000, e.code());
        assertEquals(TransferController.MAX_BODY_BYTES + 1, stream.read.get());
    }

    @Test
    void Content_Length가_한도를_넘으면_읽지_않고_거부() {
        EndlessStream stream = new EndlessStream();
        GatewayException e = assertThrows(GatewayException.class,
                () -> TransferController.readBody(request(stream, 5_000_000)));
        assertEquals(ResultCode.E1000, e.code());
        assertEquals(0, stream.read.get());
    }

    @Test
    void 로그에_남는_원인에서_줄바꿈을_지운다() {
        assertEquals("알 수 없는 암호 모드: X?FAKE ?", ErrorHandler.safe("알 수 없는 암호 모드: X\nFAKE \r"));
    }
}
