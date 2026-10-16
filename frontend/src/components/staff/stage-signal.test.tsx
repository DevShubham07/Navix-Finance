import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { StageSignal } from "./stage-signal";
import { stepInfo } from "@/lib/domain/journey-steps";

describe("stepInfo", () => {
  it("maps intake and offer steps", () => {
    expect(stepInfo("PAYSLIPS")).toMatchObject({ journey: "intake", label: "Payslips", n: 8, total: 12 });
    expect(stepInfo("OFFER_ESIGN")).toMatchObject({ journey: "offer", label: "eSign", n: 9, total: 12 });
    expect(stepInfo("OFFER_MANDATE")).toMatchObject({ journey: "offer", label: "Mandate", n: 12, total: 12 });
  });
  it("returns null for unknown/empty", () => {
    expect(stepInfo("NOPE")).toBeNull();
    expect(stepInfo(null)).toBeNull();
  });
});

describe("StageSignal", () => {
  const label = () => screen.getByRole("img").getAttribute("aria-label");
  it("DRAFT with intake step", () => {
    render(<StageSignal status="DRAFT" journeyStep="PAYSLIPS" />);
    expect(screen.getByText("Signup · Payslips (8/12)")).toBeTruthy();
  });
  it("SANCTIONED with offer step", () => {
    render(<StageSignal status="SANCTIONED" journeyStep="OFFER_ESIGN" />);
    expect(screen.getByText("Offer · eSign (9/12)")).toBeTruthy();
  });
  it("ACTIVE overdue shows DPD", () => {
    render(<StageSignal status="ACTIVE" loanStatus="OVERDUE" dpd={5} />);
    expect(screen.getByText("Overdue · 5 DPD")).toBeTruthy();
  });
  it("REJECTED", () => {
    render(<StageSignal status="REJECTED" />);
    expect(label()).toContain("Rejected");
  });
  it("CLOSED", () => {
    render(<StageSignal status="CLOSED" />);
    expect(screen.getByText("Closed")).toBeTruthy();
  });
  it("unknown step falls back to stage label", () => {
    render(<StageSignal status="DRAFT" journeyStep="WAT" />);
    expect(screen.getByText("Started")).toBeTruthy();
  });
});
