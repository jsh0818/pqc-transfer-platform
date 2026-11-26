package pqc.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "account")
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "corporation_id", nullable = false)
	private Long corporationId;

	@Column(name = "account_no", nullable = false)
	private String accountNo;

	@Column(name = "currency", nullable = false)
	private String currency = "KRW";

	@Column(name = "status", nullable = false)
	private String status = "ACTIVE";

	/** 원장 합계의 캐시. 대사 배치가 검증한다. */
	@Column(name = "balance", nullable = false)
	private long balance;

	protected Account() {
	}

	public Account(Long corporationId, String accountNo, long balance) {
		this.corporationId = corporationId;
		this.accountNo = accountNo;
		this.balance = balance;
	}

	public Long getId() {
		return id;
	}

	public Long getCorporationId() {
		return corporationId;
	}

	public String getAccountNo() {
		return accountNo;
	}

	public long getBalance() {
		return balance;
	}
}
