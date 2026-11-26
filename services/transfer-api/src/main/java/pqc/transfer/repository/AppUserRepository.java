package pqc.transfer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pqc.transfer.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
}
