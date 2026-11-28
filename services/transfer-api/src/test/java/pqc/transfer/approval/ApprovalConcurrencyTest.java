package pqc.transfer.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import pqc.transfer.domain.Account;
import pqc.transfer.domain.AppUser;
import pqc.transfer.domain.Approval;
import pqc.transfer.domain.ApprovalPolicy;
import pqc.transfer.domain.Corporation;
import pqc.transfer.domain.Decision;
import pqc.transfer.domain.TransferRequest;
import pqc.transfer.domain.TransferStatus;
import pqc.transfer.domain.UserRole;
import pqc.transfer.repository.AccountRepository;
import pqc.transfer.repository.AppUserRepository;
import pqc.transfer.repository.ApprovalPolicyRepository;
import pqc.transfer.repository.ApprovalRepository;
import pqc.transfer.repository.CorporationRepository;
import pqc.transfer.repository.TransferRequestRepository;

/**
 * 동시 결재 시험. ApprovalServiceTest와 달리 트랜잭션으로 감싸지 않는다.
 * 감싸면 모든 호출이 한 트랜잭션에서 차례로 돌아서 SELECT ... FOR UPDATE 잠금이 실제로 부딪히지 않는다.
 * 데이터는 실제로 커밋되므로 끝나면 직접 지운다.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@SpringBootTest
class ApprovalConcurrencyTest {

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
	@Autowired
	ApprovalRepository approvals;

	Corporation corp;
	long makerId;
	long checker1Id;
	long checker2Id;
	Account account;
	ApprovalPolicy policy;
	long transferId;

	@BeforeEach
	void 준비() {
		String unique = Long.toString(System.nanoTime());
		corp = corporations.save(new Corporation("동시성시험" + unique, unique, "LEGACY", "LEGACY"));
		makerId = users.save(new AppUser(corp.getId(), "m" + unique, UserRole.MAKER)).getId();
		checker1Id = users.save(new AppUser(corp.getId(), "c1" + unique, UserRole.CHECKER)).getId();
		checker2Id = users.save(new AppUser(corp.getId(), "c2" + unique, UserRole.CHECKER)).getId();
		account = accounts.save(new Account(corp.getId(), unique, 1_000_000_000L));
		policy = policies.save(new ApprovalPolicy(corp.getId(), 10_000_000, 2)); // 1천만 원 이상은 승인 2명
		transferId = transfers.save(new TransferRequest("KEY-" + unique, account.getId(), "088", "1109876543210",
				15_000_000, TransferStatus.PENDING_APPROVAL, makerId, "hash", "LEGACY")).getId();
	}

	@AfterEach
	void 정리() {
		approvals.deleteAll(approvals.findByTransferIdOrderByStepNo(transferId));
		transfers.deleteById(transferId);
		policies.delete(policy);
		accounts.delete(account);
		users.deleteAllById(List.of(makerId, checker1Id, checker2Id));
		corporations.deleteById(corp.getId());
	}

	@Test
	void 필요_승인이_2명일_때_두_결재자가_동시에_승인하면_APPROVED는_한_번만_된다() throws Exception {
		List<Object> results = runTogether(
				() -> service.decide(transferId, checker1Id, Decision.APPROVED, null),
				() -> service.decide(transferId, checker2Id, Decision.APPROVED, null));

		List<ApprovalResult> ok = results.stream().filter(ApprovalResult.class::isInstance)
				.map(ApprovalResult.class::cast).toList();
		assertEquals(2, ok.size(), "둘 다 성공해야 한다: " + results);
		assertEquals(1, ok.stream().filter(r -> r.status() == TransferStatus.APPROVED).count());
		assertEquals(1, ok.stream().filter(r -> r.status() == TransferStatus.PENDING_APPROVAL).count());

		assertEquals(TransferStatus.APPROVED, transfers.findById(transferId).orElseThrow().getStatus());
		List<Integer> steps = approvals.findByTransferIdOrderByStepNo(transferId).stream()
				.map(Approval::getStepNo).toList();
		assertEquals(List.of(1, 2), steps);
	}

	@Test
	void 같은_사람이_동시에_두_번_승인하면_하나만_성공한다() throws Exception {
		List<Object> results = runTogether(
				() -> service.decide(transferId, checker1Id, Decision.APPROVED, null),
				() -> service.decide(transferId, checker1Id, Decision.APPROVED, null));

		assertEquals(1, results.stream().filter(ApprovalResult.class::isInstance).count(), "성공은 한 번: " + results);
		List<ApprovalException> failed = results.stream().filter(ApprovalException.class::isInstance)
				.map(ApprovalException.class::cast).toList();
		assertEquals(1, failed.size(), "실패는 한 번: " + results);
		assertEquals(ErrorCode.DUPLICATE_APPROVER, failed.get(0).getErrorCode());

		assertEquals(1, approvals.findByTransferIdOrderByStepNo(transferId).size());
		assertEquals(TransferStatus.PENDING_APPROVAL, transfers.findById(transferId).orElseThrow().getStatus());
	}

	/** 두 작업을 장벽에서 맞춰 거의 동시에 시작하고, 각각의 결과(값 또는 예외)를 모아 돌려준다. */
	private static List<Object> runTogether(Callable<ApprovalResult> first, Callable<ApprovalResult> second)
			throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CyclicBarrier barrier = new CyclicBarrier(2);
			List<Future<Object>> futures = new ArrayList<>();
			for (Callable<ApprovalResult> task : List.of(first, second)) {
				futures.add(pool.submit(() -> {
					barrier.await(10, TimeUnit.SECONDS);
					try {
						return task.call();
					}
					catch (ApprovalException e) {
						return e;
					}
				}));
			}
			List<Object> results = new ArrayList<>();
			for (Future<Object> f : futures) {
				results.add(f.get(30, TimeUnit.SECONDS));
			}
			assertTrue(results.size() == 2);
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}
}
