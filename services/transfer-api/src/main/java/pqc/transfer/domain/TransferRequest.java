package pqc.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "transfer_request")
public class TransferRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "idempotency_key", nullable = false)
	private String idempotencyKey;

	@Column(name = "from_account_id", nullable = false)
	private Long fromAccountId;

	@Column(name = "to_bank_code", nullable = false)
	private String toBankCode;

	@Column(name = "to_account_no", nullable = false)
	private String toAccountNo;

	/** 원 단위 정수. */
	@Column(name = "amount", nullable = false)
	private long amount;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private TransferStatus status;

	@Column(name = "maker_user_id", nullable = false)
	private Long makerUserId;

	@Column(name = "request_hash", nullable = false)
	private String requestHash;

	@Column(name = "crypto_mode_used", nullable = false)
	private String cryptoModeUsed;

	@Column(name = "requested_at", nullable = false)
	private Instant requestedAt = Instant.now();

	protected TransferRequest() {
	}

	public TransferRequest(String idempotencyKey, Long fromAccountId, String toBankCode, String toAccountNo,
			long amount, TransferStatus status, Long makerUserId, String requestHash, String cryptoModeUsed) {
		this.idempotencyKey = idempotencyKey;
		this.fromAccountId = fromAccountId;
		this.toBankCode = toBankCode;
		this.toAccountNo = toAccountNo;
		this.amount = amount;
		this.status = status;
		this.makerUserId = makerUserId;
		this.requestHash = requestHash;
		this.cryptoModeUsed = cryptoModeUsed;
	}

	/** 승인 대기 중인 이체를 승인 완료로 바꾼다. 다른 상태에서는 부를 수 없다. */
	public void approve() {
		requirePending();
		this.status = TransferStatus.APPROVED;
	}

	/** 승인 대기 중인 이체를 거절로 바꾼다. 다른 상태에서는 부를 수 없다. */
	public void reject() {
		requirePending();
		this.status = TransferStatus.REJECTED;
	}

	private void requirePending() {
		if (status != TransferStatus.PENDING_APPROVAL) {
			throw new IllegalStateException("승인 대기 상태가 아니다: " + status);
		}
	}

	public Long getId() {
		return id;
	}

	public Long getFromAccountId() {
		return fromAccountId;
	}

	public long getAmount() {
		return amount;
	}

	public TransferStatus getStatus() {
		return status;
	}

	public Long getMakerUserId() {
		return makerUserId;
	}
}
