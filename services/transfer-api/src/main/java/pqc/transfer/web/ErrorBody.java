package pqc.transfer.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 오류 응답(openapi.yaml ErrorResponse). 결재 API에서는 transfer_id를 싣지 않는다. */
public record ErrorBody(
		@JsonProperty("result_code") String resultCode,
		@JsonProperty("message") String message) {
}
