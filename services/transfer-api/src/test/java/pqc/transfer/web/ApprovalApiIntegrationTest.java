package pqc.transfer.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import pqc.transfer.domain.Account;
import pqc.transfer.domain.AppUser;
import pqc.transfer.domain.ApprovalPolicy;
import pqc.transfer.domain.Corporation;
import pqc.transfer.domain.TransferRequest;
import pqc.transfer.domain.TransferStatus;
import pqc.transfer.domain.UserRole;
import pqc.transfer.repository.AccountRepository;
import pqc.transfer.repository.AppUserRepository;
import pqc.transfer.repository.ApprovalPolicyRepository;
import pqc.transfer.repository.CorporationRepository;
import pqc.transfer.repository.TransferRequestRepository;

/**
 * HTTP → 결재 엔진 → PostgreSQL까지 한 번에 확인한다. 실제 DB가 필요하다(CI의 transfer-api 작업).
 * 시험마다 롤백되어 DB에 남지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_PASSWORD", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApprovalApiIntegrationTest {

	@Autowired
	MockMvc mvc;
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
		Corporation corp = corporations.save(new Corporation("API시험기업", "222-22-22222", "LEGACY", "LEGACY"));
		makerId = users.save(new AppUser(corp.getId(), "api-maker", UserRole.MAKER)).getId();
		checker1Id = users.save(new AppUser(corp.getId(), "api-checker1", UserRole.CHECKER)).getId();
		checker2Id = users.save(new AppUser(corp.getId(), "api-checker2", UserRole.CHECKER)).getId();
		Account account = accounts.save(new Account(corp.getId(), "2002345678901", 1_000_000_000L));
		policies.save(new ApprovalPolicy(corp.getId(), 10_000_000, 2)); // 1천만 원 이상은 승인 2명
		transferId = transfers.save(new TransferRequest("API-KEY-" + System.nanoTime(), account.getId(), "088",
				"1109876543210", 15_000_000, TransferStatus.PENDING_APPROVAL, makerId, "hash", "LEGACY")).getId();
	}

	ResultActions decide(long userId, String body) throws Exception {
		return mvc.perform(post("/v1/transfers/" + transferId + "/approvals")
				.header(ApprovalController.DEV_USER_HEADER, Long.toString(userId))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	@Test
	void 두_결재자가_차례로_승인하면_APPROVED가_된다() throws Exception {
		decide(checker1Id, "{\"decision\":\"APPROVED\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transfer_id").value(transferId))
				.andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
				.andExpect(jsonPath("$.approvals_done").value(1))
				.andExpect(jsonPath("$.approvals_required").value(2));

		decide(checker2Id, "{\"decision\":\"APPROVED\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("APPROVED"))
				.andExpect(jsonPath("$.approvals_done").value(2));

		assertEquals(TransferStatus.APPROVED, transfers.findById(transferId).orElseThrow().getStatus());
	}

	@Test
	void MAKER는_결재할_수_없다_403_E2010() throws Exception {
		decide(makerId, "{\"decision\":\"APPROVED\"}")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.result_code").value("E2010"));
	}

	@Test
	void 사유_없는_거절은_400_E1000이고_상태가_바뀌지_않는다() throws Exception {
		decide(checker1Id, "{\"decision\":\"REJECTED\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.result_code").value("E1000"));

		assertEquals(TransferStatus.PENDING_APPROVAL, transfers.findById(transferId).orElseThrow().getStatus());
	}

	@Test
	void 같은_사람의_두_번째_결재는_409_E2009() throws Exception {
		decide(checker1Id, "{\"decision\":\"APPROVED\"}").andExpect(status().isOk());
		decide(checker1Id, "{\"decision\":\"APPROVED\"}")
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.result_code").value("E2009"));
	}
}
