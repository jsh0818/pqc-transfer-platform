package pqc.transfer.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pqc.transfer.domain.TransferRequest;

public interface TransferRequestRepository extends JpaRepository<TransferRequest, Long> {

	/** 이체 행을 잠그고(SELECT ... FOR UPDATE) 읽는다. 결재가 동시에 들어와도 한 번에 하나씩 처리된다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from TransferRequest t where t.id = :id")
	Optional<TransferRequest> findByIdForUpdate(@Param("id") Long id);
}
