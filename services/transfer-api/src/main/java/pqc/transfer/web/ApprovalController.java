package pqc.transfer.web;

import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import pqc.transfer.approval.ApprovalException;
import pqc.transfer.approval.ApprovalResult;
import pqc.transfer.approval.ApprovalService;
import pqc.transfer.approval.ErrorCode;

/**
 * POST /v1/transfers/{transferId}/approvals: 결재(승인·거절). 어드민 서버가 F7(admin-api 통로)로 부른다.
 *
 * 결재자 신원: 계약(openapi.yaml)은 JWT(bearerAuth)다. JWT 검증은 W7 어드민과 함께 만든다.
 * 그 전까지는 개발용 헤더 {@value #DEV_USER_HEADER}로 결재자 ID를 받는다. 이 헤더는 W7에서 지운다.
 * 결재자가 이 이체를 결재해도 되는지(역할, 소속 기업)는 헤더가 아니라 {@link ApprovalService}가 DB로 확인한다.
 *
 * 입력 형식은 여기서 모두 확인하고, 어긋나면 서비스를 부르지 않고 E1000으로 답한다.
 */
@RestController
public class ApprovalController {

	static final String DEV_USER_HEADER = "X-Dev-User-Id";

	/** 1 이상의 long. 앞의 0, 부호, 공백은 받지 않는다. */
	private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]{0,18}");

	private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

	private final ApprovalService approvals;

	public ApprovalController(ApprovalService approvals) {
		this.approvals = approvals;
	}

	@PostMapping(path = "/v1/transfers/{transferId}/approvals", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ApprovalResponseBody decide(@PathVariable("transferId") String transferIdText,
			@RequestHeader(name = DEV_USER_HEADER, required = false) String userIdText,
			@RequestBody(required = false) Map<String, Object> rawBody) {
		long transferId = positiveId(transferIdText);
		long checkerUserId = positiveId(userIdText);
		ApprovalRequestBody body = ApprovalRequestBody.parse(rawBody);

		ApprovalResult result = approvals.decide(transferId, checkerUserId, body.decision(), body.reason());
		log.info("결재 기록: 이체 {}, 결재자 {}, 판단 {}, 상태 {}", transferId, checkerUserId, body.decision(), result.status());
		return ApprovalResponseBody.of(transferId, result);
	}

	static long positiveId(String text) {
		if (text == null || !POSITIVE_ID.matcher(text).matches()) {
			throw invalid();
		}
		try {
			return Long.parseLong(text);
		}
		catch (NumberFormatException e) { // 19자리 중 long 범위를 넘는 값
			throw invalid();
		}
	}

	private static ApprovalException invalid() {
		return new ApprovalException(ErrorCode.INVALID_REQUEST);
	}
}
