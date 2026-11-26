package pqc.transfer.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import pqc.transfer.domain.Approval;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {

	List<Approval> findByTransferIdOrderByStepNo(Long transferId);

	boolean existsByTransferIdAndCheckerUserId(Long transferId, Long checkerUserId);
}
