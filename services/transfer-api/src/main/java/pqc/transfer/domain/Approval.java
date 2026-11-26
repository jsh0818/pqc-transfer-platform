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

/** 결재 한 단계의 기록. 한 번 쓰면 고치지 않는다. */
@Entity
@Table(name = "approval")
public class Approval {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "transfer_id", nullable = false)
	private Long transferId;

	@Column(name = "step_no", nullable = false)
	private int stepNo;

	@Column(name = "checker_user_id", nullable = false)
	private Long checkerUserId;

	@Enumerated(EnumType.STRING)
	@Column(name = "decision", nullable = false)
	private Decision decision;

	@Column(name = "reason")
	private String reason;

	@Column(name = "decided_at", nullable = false)
	private Instant decidedAt = Instant.now();

	protected Approval() {
	}

	public Approval(Long transferId, int stepNo, Long checkerUserId, Decision decision, String reason) {
		this.transferId = transferId;
		this.stepNo = stepNo;
		this.checkerUserId = checkerUserId;
		this.decision = decision;
		this.reason = reason;
	}

	public Long getTransferId() {
		return transferId;
	}

	public int getStepNo() {
		return stepNo;
	}

	public Long getCheckerUserId() {
		return checkerUserId;
	}

	public Decision getDecision() {
		return decision;
	}
}
