package pqc.transfer.domain;

/** ERD 3장의 이체 상태. 상태는 앞으로만 간다. */
public enum TransferStatus {
	REQUESTED, PENDING_APPROVAL, APPROVED, POSTED, REJECTED, FAILED
}
