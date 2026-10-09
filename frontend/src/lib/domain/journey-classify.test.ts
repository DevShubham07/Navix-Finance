import { describe, expect, it } from "vitest";
import { classifyEvent } from "@/lib/domain/journey";

describe("classifyEvent", () => {
  it("buckets assignment, decision and lifecycle events", () => {
    expect(classifyEvent({ action: "REASSIGN", toStatus: "CREDIT_EXEC_PENDING" })).toBe("assignment");
    expect(classifyEvent({ action: "ASSIGN", toStatus: null })).toBe("assignment");
    expect(classifyEvent({ action: "SANCTION", toStatus: "SANCTIONED" })).toBe("decision");
    expect(classifyEvent({ action: "KYC_APPROVE", toStatus: "KYC_APPROVED" })).toBe("decision");
    expect(classifyEvent({ action: "AUTO_REJECT_FRAUD", toStatus: "REJECTED" })).toBe("decision");
    expect(classifyEvent({ action: null, toStatus: "REJECTED" })).toBe("decision");
    expect(classifyEvent({ action: "MARK_PENDING", toStatus: "CREDIT_EXEC_PENDING" })).toBe("decision");
    expect(classifyEvent({ action: "SUBMIT_KYC", toStatus: "KYC_PENDING" })).toBe("lifecycle");
    expect(classifyEvent({ action: "STEP_LINK_SENT", toStatus: "CREDIT_EXEC_PENDING" })).toBe("lifecycle");
  });
});
