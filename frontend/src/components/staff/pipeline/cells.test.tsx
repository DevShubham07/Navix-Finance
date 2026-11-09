/**
 * `dpdFor` / `DueCell`: a settled loan's past due date is history, not arrears.
 *
 * The bug this pins: a borrower who repaid late keeps that due date on file forever, so a register
 * keyed on the date alone showed a live `+37d` in red against a customer who owes nothing.
 */
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DueCell, dpdFor } from "@/components/staff/pipeline/cells";

/** Comfortably past, whatever day the suite runs. */
const PAST = "2020-01-01";
const FUTURE = "2099-01-01";

describe("dpdFor", () => {
  it("counts the days on a live loan", () => {
    expect(dpdFor(PAST, "ACTIVE")).toBeGreaterThan(0);
    expect(dpdFor(PAST, "OVERDUE")).toBeGreaterThan(0);
    expect(dpdFor(PAST, "IN_COLLECTIONS")).toBeGreaterThan(0);
    // No loan in hand (a file still in credit) still counts — the date is all there is.
    expect(dpdFor(PAST)).toBeGreaterThan(0);
  });

  it("counts nothing once the loan has settled, however late it was repaid", () => {
    for (const status of ["CLOSED", "REPAID", "WRITTEN_OFF", "DECLINED"]) {
      expect(dpdFor(PAST, status)).toBe(0);
    }
  });

  it("counts nothing before the due date or without one", () => {
    expect(dpdFor(FUTURE, "ACTIVE")).toBe(0);
    expect(dpdFor(null, "ACTIVE")).toBe(0);
  });
});

describe("DueCell", () => {
  it("shows the date and the arrears on a live overdue loan", () => {
    render(<DueCell dueDate={PAST} loanStatus="ACTIVE" />);
    expect(screen.getByTitle("Days past due")).toBeInTheDocument();
  });

  it("shows a closed loan's date with no arrears beside it", () => {
    render(<DueCell dueDate={PAST} loanStatus="CLOSED" />);
    expect(screen.queryByTitle("Days past due")).not.toBeInTheDocument();
    // The date itself still renders — it is the loan's history, just not in red.
    expect(screen.getByText(/2020/)).toBeInTheDocument();
  });
});
