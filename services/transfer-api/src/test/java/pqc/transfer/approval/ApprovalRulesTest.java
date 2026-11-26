package pqc.transfer.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import pqc.transfer.domain.ApprovalPolicy;

class ApprovalRulesTest {

	private final List<ApprovalPolicy> tiers = List.of(
			new ApprovalPolicy(1L, 0, 0),
			new ApprovalPolicy(1L, 10_000_000, 1),
			new ApprovalPolicy(1L, 100_000_000, 2));

	@Test
	void 소액은_승인이_필요없다() {
		assertEquals(0, ApprovalRules.requiredApprovals(tiers, 1_500_000));
	}

	@Test
	void 구간_경계_금액은_그_구간에_속한다() {
		assertEquals(0, ApprovalRules.requiredApprovals(tiers, 9_999_999));
		assertEquals(1, ApprovalRules.requiredApprovals(tiers, 10_000_000));
		assertEquals(1, ApprovalRules.requiredApprovals(tiers, 99_999_999));
		assertEquals(2, ApprovalRules.requiredApprovals(tiers, 100_000_000));
	}

	@Test
	void 가장_높은_구간이_적용된다() {
		assertEquals(2, ApprovalRules.requiredApprovals(tiers, 5_000_000_000L));
	}

	@Test
	void 구간_순서와_상관없이_같은_결과다() {
		List<ApprovalPolicy> shuffled = List.of(tiers.get(2), tiers.get(0), tiers.get(1));
		assertEquals(1, ApprovalRules.requiredApprovals(shuffled, 50_000_000));
	}

	@Test
	void 정책이_없으면_승인이_필요없다() {
		assertEquals(0, ApprovalRules.requiredApprovals(List.of(), 1_000_000_000));
	}
}
