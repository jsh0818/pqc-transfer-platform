package pqc.transfer.approval;

import pqc.transfer.domain.TransferStatus;

/** 결재 한 건을 처리한 결과. */
public record ApprovalResult(TransferStatus status, int approvedCount, int requiredApprovals) {
}
