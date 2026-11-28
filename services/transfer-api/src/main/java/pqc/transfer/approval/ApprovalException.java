package pqc.transfer.approval;

/** 결재 규칙을 어겼을 때 던진다. API 계층이 errorCode로 HTTP 상태와 result_code를 만든다. */
public class ApprovalException extends RuntimeException {

	private final ErrorCode errorCode;

	public ApprovalException(ErrorCode errorCode) {
		super(errorCode.message());
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}
}
