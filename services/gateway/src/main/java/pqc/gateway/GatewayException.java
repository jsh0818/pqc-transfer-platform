package pqc.gateway;

/**
 * 검증 실패. code는 외부 응답에, reason은 내부 로그에만 쓴다(reason을 응답에 넣지 않는다).
 */
public class GatewayException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ResultCode code;

    public GatewayException(ResultCode code, String reason) {
        super(reason);
        this.code = code;
    }

    public GatewayException(ResultCode code, String reason, Throwable cause) {
        super(reason, cause);
        this.code = code;
    }

    public ResultCode code() {
        return code;
    }
}
