package pqc.transfer.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import pqc.transfer.approval.ApprovalException;
import pqc.transfer.approval.ApprovalResult;
import pqc.transfer.approval.ApprovalService;
import pqc.transfer.approval.ErrorCode;
import pqc.transfer.domain.Decision;
import pqc.transfer.domain.TransferStatus;

/** 결재 API의 입력 확인과 응답 형식. 결재 엔진은 가짜로 두고, DB 없이 돈다. */
@WebMvcTest(ApprovalController.class)
class ApprovalControllerTest {

	static final String URL = "/v1/transfers/52001/approvals";
	static final String USER = ApprovalController.DEV_USER_HEADER;

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ApprovalService service;

	ResultActions send(String url, String userId, MediaType type, String body) throws Exception {
		var request = post(url).contentType(type).content(body);
		if (userId != null) {
			request.header(USER, userId);
		}
		return mvc.perform(request);
	}

	ResultActions send(String body) throws Exception {
		return send(URL, "7", MediaType.APPLICATION_JSON, body);
	}

	void expectInvalid(ResultActions result) throws Exception {
		result.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.result_code").value("E1000"))
				.andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REQUEST.message()));
		verify(service, never()).decide(anyLong(), anyLong(), any(), any());
	}

	// 정상

	@Test
	void 승인하면_200과_결재_결과를_snake_case로_준다() throws Exception {
		when(service.decide(52001L, 7L, Decision.APPROVED, null))
				.thenReturn(new ApprovalResult(TransferStatus.PENDING_APPROVAL, 1, 2));

		send("{\"decision\":\"APPROVED\"}")
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.transfer_id").value(52001))
				.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
				.andExpect(jsonPath("$.approvals_done").value(1))
				.andExpect(jsonPath("$.approvals_required").value(2));
	}

	@Test
	void 거절_사유는_그대로_엔진에_넘긴다() throws Exception {
		when(service.decide(52001L, 7L, Decision.REJECTED, "금액 확인 필요 🙏"))
				.thenReturn(new ApprovalResult(TransferStatus.REJECTED, 0, 2));

		send("{\"decision\":\"REJECTED\",\"reason\":\"금액 확인 필요 🙏\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("REJECTED"));
	}

	@Test
	void 사유는_200자까지_받는다_한글과_이모지도_한_글자() throws Exception {
		String reason = "가".repeat(199) + "🙏";
		when(service.decide(52001L, 7L, Decision.REJECTED, reason))
				.thenReturn(new ApprovalResult(TransferStatus.REJECTED, 0, 1));

		send("{\"decision\":\"REJECTED\",\"reason\":\"" + reason + "\"}").andExpect(status().isOk());
	}

	// 엔진의 결과 코드 → HTTP 상태

	@ParameterizedTest
	@EnumSource(ErrorCode.class)
	void 엔진_오류는_정해진_HTTP_상태와_결과_코드로_바꾼다(ErrorCode code) throws Exception {
		when(service.decide(anyLong(), anyLong(), any(), any())).thenThrow(new ApprovalException(code));

		send("{\"decision\":\"APPROVED\"}")
				.andExpect(status().is(code.httpStatus()))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.result_code").value(code.code()))
				.andExpect(jsonPath("$.message").value(code.message()));
	}

	@Test
	void 예상하지_못한_오류는_500_E9000이고_내용을_밖에_내보내지_않는다() throws Exception {
		when(service.decide(anyLong(), anyLong(), any(), isNull()))
				.thenThrow(new IllegalStateException("org.postgresql 내부 오류 비밀값"));

		send("{\"decision\":\"APPROVED\"}")
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.result_code").value("E9000"))
				.andExpect(jsonPath("$.message").value("서버 내부 오류입니다"))
				.andExpect(content().string(not(containsString("비밀값"))))
				.andExpect(content().string(not(containsString("IllegalStateException"))));
	}

	// 결재자 헤더와 경로

	@Test
	void 결재자_헤더가_없으면_E1000() throws Exception {
		expectInvalid(send(URL, null, MediaType.APPLICATION_JSON, "{\"decision\":\"APPROVED\"}"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "abc", "0", "-1", "+7", "07", " 7", "1.5", "9223372036854775808" })
	void 결재자_ID가_1_이상의_정수가_아니면_E1000(String userId) throws Exception {
		expectInvalid(send(URL, userId, MediaType.APPLICATION_JSON, "{\"decision\":\"APPROVED\"}"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "abc", "0", "-1", "9223372036854775808" })
	void 이체_ID가_1_이상의_정수가_아니면_E1000(String transferId) throws Exception {
		expectInvalid(send("/v1/transfers/" + transferId + "/approvals", "7", MediaType.APPLICATION_JSON,
				"{\"decision\":\"APPROVED\"}"));
	}

	@Test
	void long_최댓값_ID는_받는다() throws Exception {
		when(service.decide(Long.MAX_VALUE, Long.MAX_VALUE, Decision.APPROVED, null))
				.thenReturn(new ApprovalResult(TransferStatus.APPROVED, 1, 1));

		send("/v1/transfers/9223372036854775807/approvals", "9223372036854775807", MediaType.APPLICATION_JSON,
				"{\"decision\":\"APPROVED\"}").andExpect(status().isOk());
	}

	// 본문

	@ParameterizedTest
	@ValueSource(strings = {
			"", // 빈 본문
			"null",
			"{", // 깨진 JSON
			"[]",
			"{}", // decision 없음
			"{\"decision\":null}",
			"{\"decision\":\"MAYBE\"}", // 허용되지 않은 값
			"{\"decision\":\"approved\"}", // 소문자
			"{\"decision\":1}",
			"{\"decision\":\"APPROVED\",\"reason\":3}",
			"{\"decision\":\"APPROVED\",\"amount\":\"1\"}" // 정의되지 않은 항목
	})
	void 본문이_계약과_다르면_E1000(String body) throws Exception {
		expectInvalid(send(body));
	}

	@Test
	void 사유가_200자를_넘으면_E1000() throws Exception {
		expectInvalid(send("{\"decision\":\"REJECTED\",\"reason\":\"" + "가".repeat(201) + "\"}"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "\\u0000", "\\n", "\\t", "\\u007f", "\\u0085", "\\ud800", "\\udc00", "가\\ud83d" })
	void 사유에_제어_문자나_짝_없는_서로게이트가_있으면_E1000(String escaped) throws Exception {
		expectInvalid(send("{\"decision\":\"REJECTED\",\"reason\":\"사유" + escaped + "끝\"}"));
	}

	@Test
	void JSON이_아닌_Content_Type은_E1000() throws Exception {
		expectInvalid(send(URL, "7", MediaType.TEXT_PLAIN, "{\"decision\":\"APPROVED\"}"));
	}
}
