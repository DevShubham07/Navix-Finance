import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { StageSignal } from "./stage-signal";
import { stepInfo } from "@/lib/domain/journey-steps";

describe("stepInfo", () => {
  it("maps intake and offer steps", () => {
    expect(stepInfo("PAYSLIPS")).toMatchObject({ journey: "intake", label: "Payslips", n: 9, total: 13 });
    expect(stepInfo("OFFER_ESIGN")).toMatchObject({ journey: "offer", label: "eSign", n: 11, total: 14 });
    expect(stepInfo("OFFER_MANDATE")).toMatchObject({ journey: "offer", label: "Mandate", n: 14, total: 14 });
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
    expect(screen.getByText("Signup · Payslips (9/13)")).toBeTruthy();
  });
  it("SANCTIONED with offer step", () => {
    render(<StageSignal status="SANCTIONED" journeyStep="OFFER_ESIGN" />);
    expect(screen.getByText("Offer · eSign (11/14)")).toBeTruthy();
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
