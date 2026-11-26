package pqc.transfer.approval;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pqc.transfer.domain.Account;
import pqc.transfer.domain.Approval;
import pqc.transfer.domain.Decision;
import pqc.transfer.domain.TransferRequest;
import pqc.transfer.domain.TransferStatus;
import pqc.transfer.repository.AccountRepository;
import pqc.transfer.repository.ApprovalPolicyRepository;
import pqc.transfer.repository.ApprovalRepository;
import pqc.transfer.repository.TransferRequestRepository;

/**
 * 결재(maker-checker) 엔진. 위협 모델 T6(내부자 단독 이체)의 방어선이다.
 *
 * 여기서 지키는 규칙: 만든 사람은 승인할 수 없다(R4), 이미 처리된 이체는 결재할 수 없다,
 * 한 사람이 같은 이체를 두 번 결재할 수 없다, 필요한 승인 수가 차야 APPROVED가 된다.
 * 결재자가 누구인지(역할, 소속 기업)는 인증 계층(W7 어드민)이 확인한다. 이 서비스는 모른다.
 */
@Service
public class ApprovalService {

	private final TransferRequestRepository transfers;
	private final ApprovalRepository approvals;
	private final ApprovalPolicyRepository policies;
	private final AccountRepository accounts;

	public ApprovalService(TransferRequestRepository transfers, ApprovalRepository approvals,
			ApprovalPolicyRepository policies, AccountRepository accounts) {
		this.transfers = transfers;
		this.approvals = approvals;
		this.policies = policies;
		this.accounts = accounts;
	}

	@Transactional
	public ApprovalResult decide(long transferId, long checkerUserId, Decision decision, String reason) {
		// 이체 행을 잠가서 같은 이체에 대한 동시 결재를 한 번에 하나씩 처리한다.
		TransferRequest transfer = transfers.findByIdForUpdate(transferId)
				.orElseThrow(() -> new ApprovalException(ErrorCode.NOT_FOUND));

		if (transfer.getStatus() != TransferStatus.PENDING_APPROVAL) {
			throw new ApprovalException(ErrorCode.ALREADY_PROCESSED);
		}
		if (transfer.getMakerUserId().equals(checkerUserId)) {
			throw new ApprovalException(ErrorCode.SAME_USER);
		}
		if (approvals.existsByTransferIdAndCheckerUserId(transferId, checkerUserId)) {
			throw new ApprovalException(ErrorCode.ALREADY_PROCESSED);
		}

		List<Approval> previous = approvals.findByTransferIdOrderByStepNo(transferId);
		approvals.save(new Approval(transferId, previous.size() + 1, checkerUserId, decision, reason));

		int required = requiredApprovals(transfer);
		int approved = (int) previous.stream().filter(a -> a.getDecision() == Decision.APPROVED).count();

		if (decision == Decision.REJECTED) {
			transfer.reject();
		}
		else {
			approved++;
			if (approved >= required) {
				transfer.approve();
			}
		}
		return new ApprovalResult(transfer.getStatus(), approved, required);
	}

	/** 이 이체에 필요한 승인 수. 승인 대기 상태인 이체는 최소 1명은 필요하다고 본다. */
	private int requiredApprovals(TransferRequest transfer) {
		Account account = accounts.findById(transfer.getFromAccountId())
				.orElseThrow(() -> new IllegalStateException("출금 계좌가 없다: " + transfer.getFromAccountId()));
		int required = ApprovalRules.requiredApprovals(
				policies.findByCorporationId(account.getCorporationId()), transfer.getAmount());
		return Math.max(required, 1);
	}
}
