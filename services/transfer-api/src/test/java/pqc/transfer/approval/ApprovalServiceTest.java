package pqc.transfer.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import pqc.transfer.domain.Account;
import pqc.transfer.domain.AppUser;
import pqc.transfer.domain.ApprovalPolicy;
import pqc.transfer.domain.Corporation;
import pqc.transfer.domain.Decision;
import pqc.transfer.domain.TransferRequest;
import pqc.transfer.domain.TransferStatus;
import pqc.transfer.domain.UserRole;
import pqc.transfer.repository.AccountRepository;
import pqc.transfer.repository.AppUserRepository;
import pqc.transfer.repository.ApprovalPolicyRepository;
import pqc.transfer.repository.CorporationRepository;
import pqc.transfer.repository.TransferRequestRepository;

/**
 * 실제 PostgreSQL이 필요하다(README의 로컬 실행 1, 2번). 시험마다 롤백되어 DB에 남지 않는다.
 * POSTGRES_PASSWORD 환경변수가 있을 때만 실행한다.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@SpringBootTest
@Transactional
class ApprovalServiceTest {

	@Autowired
	ApprovalService service;
	@Autowired
	CorporationRepository corporations;
	@Autowired
	AppUserRepository users;
	@Autowired
	AccountRepository accounts;
	@Autowired
	ApprovalPolicyRepository policies;
	@Autowired
	TransferRequestRepository transfers;

	long makerId;
	long checker1Id;
	long checker2Id;
	long transferId;

	@BeforeEach
	void 준비() {
		Corporation corp = corporations.save(new Corporation("테스트기업", "000-00-00000", "LEGACY", "LEGACY"));
		makerId = users.save(new AppUser(corp.getId(), "maker1", UserRole.MAKER)).getId();
		checker1Id = users.save(new AppUser(corp.getId(), "checker1", UserRole.CHECKER)).getId();
		checker2Id = users.save(new AppUser(corp.getId(), "checker2", UserRole.CHECKER)).getId();
		Account account = accounts.save(new Account(corp.getId(), "1002345678901", 1_000_000_000L));
		// 1천만 원 이상은 승인 2명
		policies.save(new ApprovalPolicy(corp.getId(), 10_000_000, 2));
		transferId = transfers.save(new TransferRequest("KEY-" + System.nanoTime(), account.getId(), "088",
				"1109876543210", 15_000_000, TransferStatus.PENDING_APPROVAL, makerId, "hash", "LEGACY")).getId();
	}

	@Test
	void 만든_사람은_자기_이체를_승인할_수_없다() {
		ApprovalException e = assertThrows(ApprovalException.class,
				() -> service.decide(transferId, makerId, Decision.APPROVED, null));
		assertEquals(ErrorCode.SAME_USER, e.getErrorCode());
	}

	@Test
	void 필요한_승인_수가_차야_승인된다() {
		ApprovalResult first = service.decide(transferId, checker1Id, Decision.APPROVED, null);
		assertEquals(TransferStatus.PENDING_APPROVAL, first.status());
		assertEquals(1, first.approvedCount());
		assertEquals(2, first.requiredApprovals());

		ApprovalResult second = service.decide(transferId, checker2Id, Decision.APPROVED, null);
		assertEquals(TransferStatus.APPROVED, second.status());
	}

	@Test
	void 같은_사람이_두_번_결재할_수_없다() {
		service.decide(transferId, checker1Id, Decision.APPROVED, null);
		ApprovalException e = assertThrows(ApprovalException.class,
				() -> service.decide(transferId, checker1Id, Decision.APPROVED, null));
		assertEquals(ErrorCode.ALREADY_PROCESSED, e.getErrorCode());
	}

	@Test
	void 거절하면_바로_거절되고_이후_결재는_막힌다() {
		ApprovalResult rejected = service.decide(transferId, checker1Id, Decision.REJECTED, "금액 확인 필요");
		assertEquals(TransferStatus.REJECTED, rejected.status());

		ApprovalException e = assertThrows(ApprovalException.class,
				() -> service.decide(transferId, checker2Id, Decision.APPROVED, null));
		assertEquals(ErrorCode.ALREADY_PROCESSED, e.getErrorCode());
	}

	@Test
	void 없는_이체는_찾을_수_없다() {
		ApprovalException e = assertThrows(ApprovalException.class,
				() -> service.decide(999_999_999L, checker1Id, Decision.APPROVED, null));
		assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
	}
}
