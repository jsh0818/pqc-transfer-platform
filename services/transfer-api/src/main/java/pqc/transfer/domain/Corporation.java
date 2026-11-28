package pqc.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "corporation")
public class Corporation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "name", nullable = false)
	private String name;

	@Column(name = "biz_reg_no", nullable = false)
	private String bizRegNo;

	@Column(name = "crypto_mode", nullable = false)
	private String cryptoMode;

	@Column(name = "crypto_floor", nullable = false)
	private String cryptoFloor;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt = Instant.now();

	protected Corporation() {
	}

	public Corporation(String name, String bizRegNo, String cryptoMode, String cryptoFloor) {
		this.name = name;
		this.bizRegNo = bizRegNo;
		this.cryptoMode = cryptoMode;
		this.cryptoFloor = cryptoFloor;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getCryptoMode() {
		return cryptoMode;
	}

	public String getCryptoFloor() {
		return cryptoFloor;
	}
}
