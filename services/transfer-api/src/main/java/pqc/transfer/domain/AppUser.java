package pqc.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_user")
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "corporation_id", nullable = false)
	private Long corporationId;

	@Column(name = "login_id", nullable = false)
	private String loginId;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false)
	private UserRole role;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	protected AppUser() {
	}

	public AppUser(Long corporationId, String loginId, UserRole role) {
		this.corporationId = corporationId;
		this.loginId = loginId;
		this.role = role;
	}

	public Long getId() {
		return id;
	}

	public Long getCorporationId() {
		return corporationId;
	}

	public UserRole getRole() {
		return role;
	}

	public boolean isActive() {
		return active;
	}

	public void deactivate() {
		this.active = false;
	}
}
