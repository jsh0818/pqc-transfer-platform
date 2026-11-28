package pqc.transfer.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import pqc.transfer.approval.ApprovalException;
import pqc.transfer.approval.ErrorCode;

/**
 * 예외를 openapi.yaml ErrorResponse({result_code, message})로 바꾼다.
 *
 * 외부 message는 ErrorCode에 정해 둔 고정 문구만 쓴다. 예외 메시지, 입력값, 내부 클래스 이름은 응답에 넣지 않는다.
 * 없는 경로(404), 허용되지 않은 메서드(405)는 이 API의 계약 밖이라 Spring 기본 응답에 맡긴다.
 */
@RestControllerAdvice
public class ApiErrorHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

	static final ErrorBody SERVER_ERROR = new ErrorBody("E9000", "서버 내부 오류입니다");

	@ExceptionHandler(ApprovalException.class)
	ResponseEntity<ErrorBody> approval(ApprovalException e) {
		ErrorCode code = e.getErrorCode();
		log.info("결재 거부: {}", code.code());
		return body(HttpStatus.valueOf(code.httpStatus()), new ErrorBody(code.code(), code.message()));
	}

	/** 깨진 JSON, 빈 본문, 모르는 항목, decision의 허용되지 않은 값 등 본문을 읽지 못한 경우. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ErrorBody> unreadable(HttpMessageNotReadableException e) {
		log.info("본문을 읽지 못함: {}", e.getClass().getSimpleName());
		return invalid();
	}

	/** Content-Type이 application/json이 아닌 경우. 계약에 415가 없어 400 E1000으로 답한다. */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	ResponseEntity<ErrorBody> mediaType(HttpMediaTypeNotSupportedException e) {
		return invalid();
	}

	/** 그 밖의 실행 중 오류(DB 장애, 제약 위반 등). 원인은 로그에만 남긴다. */
	@ExceptionHandler(RuntimeException.class)
	ResponseEntity<ErrorBody> unexpected(RuntimeException e) {
		log.error("처리 중 오류", e);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, SERVER_ERROR);
	}

	private static ResponseEntity<ErrorBody> invalid() {
		ErrorCode code = ErrorCode.INVALID_REQUEST;
		return body(HttpStatus.valueOf(code.httpStatus()), new ErrorBody(code.code(), code.message()));
	}

	private static ResponseEntity<ErrorBody> body(HttpStatus status, ErrorBody body) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
	}
}
