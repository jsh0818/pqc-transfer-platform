package pqc.gateway;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 실패 응답: {"result_code": ..., "message": ...}. 원인(reason)은 응답에 넣지 않고 로그에만 남긴다.
 * 로그에는 키나 서명값, 본문을 남기지 않는다(T9). 원인에 섞인 요청 값의 줄바꿈 등은 지워 로그 위조를 막는다.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, String>> handle(GatewayException e) {
        if (e.code() == ResultCode.E9000) {
            log.error("거부 {}: {}", e.code(), safe(e.getMessage()), e);
        } else {
            log.warn("거부 {}: {}", e.code(), safe(e.getMessage()));
        }
        return body(e.code().httpStatus(), e.code());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, String>> unsupported(HttpMediaTypeNotSupportedException e) {
        log.warn("거부 E1000: Content-Type {}", safe(String.valueOf(e.getContentType())));
        return body(ResultCode.E1000.httpStatus(), ResultCode.E1000);
    }

    /**
     * 그 밖의 예외. 없는 경로(404), 허용되지 않은 메서드(405)처럼 Spring이 상태를 정한 요청 오류는 그 상태로 답하고
     * 서버 오류로 남기지 않는다(스캐너가 아무 경로나 찔러도 ERROR 로그가 쌓이지 않게).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(Exception e) {
        if (e instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            return requestError(response);
        }
        log.error("거부 E9000: 예상하지 못한 오류", e);
        return body(ResultCode.E9000.httpStatus(), ResultCode.E9000);
    }

    private static ResponseEntity<Map<String, String>> requestError(ErrorResponse e) {
        int status = e.getStatusCode().value();
        log.warn("거부 E1000: 요청 오류 {}", status);
        return body(status, ResultCode.E1000);
    }

    private static ResponseEntity<Map<String, String>> body(int status, ResultCode code) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("result_code", code.name(), "message", code.message()));
    }

    /** 제어 문자(줄바꿈 등)를 '?'로 바꾼다. */
    static String safe(String text) {
        return text == null ? null : text.replaceAll("\\p{Cntrl}", "?");
    }
}
