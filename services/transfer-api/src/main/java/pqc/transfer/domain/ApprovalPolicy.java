package pqc.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** "이 금액 이상이면 승인 N명" 규칙 한 줄. 기업마다 금액 구간별로 여러 줄을 둔다. */
@Entity
@Table(name = "approval_policy")
public class ApprovalPolicy {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "corporation_id", nullable = false)
	private Long corporationId;

	@Column(name = "amount_from", nullable = false)
	private long amountFrom;

	@Column(name = "required_approvals", nullable = false)
	private int requiredApprovals;

	protected ApprovalPolicy() {
	}

	public ApprovalPolicy(Long corporationId, long amountFrom, int requiredApprovals) {
		this.corporationId = corporationId;
		this.amountFrom = amountFrom;
		this.requiredApprovals = requiredApprovals;
	}

	public Long getCorporationId() {
		return corporationId;
	}

	public long getAmountFrom() {
		return amountFrom;
	}

	public int getRequiredApprovals() {
		return requiredApprovals;
	}
}
