package pqc.gateway;

/**
 * 게이트웨이가 직접 내는 결과 코드(docs/api/message-spec.md 4장). HTTP 상태는 openapi.yaml의 대응표를 따른다.
 * 보안 검사 실패(E1001~E1003)는 외부 메시지에 원인을 담지 않는다. 원인은 로그에만 남긴다.
 */
public enum ResultCode {
    E1000(400, "요청 형식이 올바르지 않습니다"),
    E1001(401, "요청을 확인할 수 없습니다"),
    E1002(401, "요청을 확인할 수 없습니다"),
    E1003(403, "요청을 확인할 수 없습니다"),
    E9000(500, "서버 내부 오류입니다");

    private final int httpStatus;
    private final String message;

    ResultCode(int httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** 외부에 돌려주는 메시지. */
    public String message() {
        return message;
    }
}
