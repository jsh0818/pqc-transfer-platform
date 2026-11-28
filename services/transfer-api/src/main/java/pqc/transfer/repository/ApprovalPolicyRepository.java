package pqc.transfer.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import pqc.transfer.domain.ApprovalPolicy;

public interface ApprovalPolicyRepository extends JpaRepository<ApprovalPolicy, Long> {

	List<ApprovalPolicy> findByCorporationId(Long corporationId);
}
