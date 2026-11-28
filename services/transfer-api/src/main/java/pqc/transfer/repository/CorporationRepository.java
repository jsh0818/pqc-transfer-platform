package pqc.transfer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pqc.transfer.domain.Corporation;

public interface CorporationRepository extends JpaRepository<Corporation, Long> {
}
