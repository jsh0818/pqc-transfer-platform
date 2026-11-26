package pqc.transfer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pqc.transfer.domain.Account;

public interface AccountRepository extends JpaRepository<Account, Long> {
}
