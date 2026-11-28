package pqc.transfer.approval;

/** docs/api/message-spec.md 4장의 결과 코드 중 결재 엔진이 쓰는 것. */
public enum ErrorCode {
	INVALID_REQUEST("E1000", 400, "요청 형식이 올바르지 않습니다"),
	SAME_USER("E2006", 403, "작성자와 승인자가 같습니다"),
	ALREADY_PROCESSED("E2007", 409, "이미 처리된 이체입니다"),
	NOT_FOUND("E2008", 404, "이체를 찾을 수 없습니다"),
	DUPLICATE_APPROVER("E2009", 409, "이미 결재한 이체입니다"),
	NOT_AUTHORIZED("E2010", 403, "결재 권한이 없습니다");

	private final String code;
	private final int httpStatus;
	private final String message;

	ErrorCode(String code, int httpStatus, String message) {
		this.code = code;
		this.httpStatus = httpStatus;
		this.message = message;
	}

	public String code() {
		return code;
	}

	public int httpStatus() {
		return httpStatus;
	}

	public String message() {
		return message;
	}
}
