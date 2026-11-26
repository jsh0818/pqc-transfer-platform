package pqc.transfer.approval;

import java.util.List;
import pqc.transfer.domain.ApprovalPolicy;

/** DB 없이 시험할 수 있는 순수 규칙. */
public final class ApprovalRules {

	private ApprovalRules() {
	}

	/**
	 * 금액에 해당하는 필요한 승인 수. "이 금액 이상이면" 구간 중 amountFrom이 가장 큰 것을 쓴다.
	 * 해당하는 구간이 없으면 0(승인 불필요)이다.
	 */
	public static int requiredApprovals(List<ApprovalPolicy> tiers, long amount) {
		long bestFrom = -1;
		int required = 0;
		for (ApprovalPolicy tier : tiers) {
			if (tier.getAmountFrom() <= amount && tier.getAmountFrom() > bestFrom) {
				bestFrom = tier.getAmountFrom();
				required = tier.getRequiredApprovals();
			}
		}
		return required;
	}
}
