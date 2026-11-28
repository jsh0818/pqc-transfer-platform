package pqc.transfer.web;

import java.util.Map;
import java.util.Set;
import pqc.transfer.approval.ApprovalException;
import pqc.transfer.approval.ErrorCode;
import pqc.transfer.domain.Decision;

/**
 * POST /v1/transfers/{transferId}/approvals 요청 본문(openapi.yaml ApprovalRequest).
 *
 * 본문을 Map으로 받아 직접 확인한다. Jackson에 맡기면 설정에 따라 숫자가 enum 순번으로 바뀌거나("decision": 1 → REJECTED)
 * 숫자가 문자열로 바뀌거나 모르는 항목이 무시될 수 있다. 결재처럼 돈이 걸린 입력은 계약과 정확히 같아야 받는다.
 */
public record ApprovalRequestBody(Decision decision, String reason) {

	/** openapi.yaml ApprovalRequest.reason maxLength, DB approval.reason varchar(200)과 같다(둘 다 문자 수 기준). */
	static final int REASON_MAX = 200;

	private static final Set<String> FIELDS = Set.of("decision", "reason");

	static ApprovalRequestBody parse(Map<String, Object> body) {
		if (body == null || !FIELDS.containsAll(body.keySet())) {
			throw invalid();
		}
		Object value = body.get("decision");
		Decision decision;
		if ("APPROVED".equals(value)) {
			decision = Decision.APPROVED;
		}
		else if ("REJECTED".equals(value)) {
			decision = Decision.REJECTED;
		}
		else {
			throw invalid(); // 없음, null, 숫자, 소문자, 그 밖의 값
		}
		Object reason = body.get("reason");
		if (reason != null && !(reason instanceof String)) {
			throw invalid();
		}
		checkReason((String) reason);
		return new ApprovalRequestBody(decision, (String) reason);
	}

	/**
	 * 게이트웨이(#24)의 문자열 공통 제약과 같다. 제어 문자(U+0000~U+001F, U+007F~U+009F)와 짝 없는 서로게이트는 받지 않는다.
	 * NUL은 PostgreSQL에 저장할 수 없고, 줄바꿈은 로그와 어드민 화면을 어지럽힌다.
	 * 길이는 DB(varchar)처럼 문자(코드 포인트) 수로 센다.
	 */
	static void checkReason(String reason) {
		if (reason == null) {
			return;
		}
		if (reason.codePointCount(0, reason.length()) > REASON_MAX) {
			throw invalid();
		}
		for (int i = 0; i < reason.length(); i++) {
			char c = reason.charAt(i);
			if (Character.isISOControl(c) || Character.isLowSurrogate(c)) {
				throw invalid();
			}
			if (Character.isHighSurrogate(c)) {
				if (i + 1 < reason.length() && Character.isLowSurrogate(reason.charAt(i + 1))) {
					i++; // 짝이 맞는 서로게이트(이모지 등)
					continue;
				}
				throw invalid();
			}
		}
	}

	private static ApprovalException invalid() {
		return new ApprovalException(ErrorCode.INVALID_REQUEST);
	}
}
