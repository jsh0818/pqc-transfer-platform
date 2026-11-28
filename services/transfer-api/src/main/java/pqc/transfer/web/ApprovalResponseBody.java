package pqc.transfer.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import pqc.transfer.approval.ApprovalResult;
import pqc.transfer.domain.TransferStatus;

/** 결재 응답(openapi.yaml ApprovalResponse). */
public record ApprovalResponseBody(
		@JsonProperty("transfer_id") long transferId,
		@JsonProperty("status") TransferStatus status,
		@JsonProperty("approvals_done") int approvalsDone,
		@JsonProperty("approvals_required") int approvalsRequired) {

	static ApprovalResponseBody of(long transferId, ApprovalResult result) {
		return new ApprovalResponseBody(transferId, result.status(), result.approvedCount(), result.requiredApprovals());
	}
}
