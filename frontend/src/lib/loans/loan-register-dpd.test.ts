import { describe, expect, it } from "vitest";
import { loanRegisterDpd, loanRegisterDpdLabel, type LoanRegisterDpdRow } from "./loan-register-dpd";

// 2026-10-06 00:30 IST — still the 5th in UTC, so a UTC "today" would get every case below wrong.
const JUST_AFTER_IST_MIDNIGHT = new Date("2026-10-05T19:00:00Z");
// 2026-10-05 23:59 IST.
const JUST_BEFORE_IST_MIDNIGHT = new Date("2026-10-05T18:29:00Z");

const live = (dueDate: string | null, dpd = 0): LoanRegisterDpdRow => ({
  dpd,
  dueDate,
  closedOn: null,
  status: "ACTIVE",
});

describe("loanRegisterDpd", () => {
  it("a positive server DPD is overdue by that many days", () => {
    expect(loanRegisterDpd({ ...live("2026-10-01", 5), status: "IN_COLLECTIONS" }, JUST_AFTER_IST_MIDNIGHT)).toEqual({
      kind: "overdue",
      days: 5,
    });
  });

  it("keeps a closed loan's frozen lateness", () => {
    const row = { dpd: 3, dueDate: "2026-09-01", closedOn: "2026-09-04", status: "CLOSED" };
    expect(loanRegisterDpd(row, JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "overdue", days: 3 });
  });

  it("due date equal to the IST day is due today, even while UTC is still the day before", () => {
    expect(loanRegisterDpd(live("2026-10-06"), JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "due-today" });
  });

  it("the UTC day is not today once IST has rolled over", () => {
    expect(loanRegisterDpd(live("2026-10-05"), JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "none" });
  });

  it("is due today up to the last IST minute of the day", () => {
    expect(loanRegisterDpd(live("2026-10-05"), JUST_BEFORE_IST_MIDNIGHT)).toEqual({ kind: "due-today" });
  });

  it("a future due date is not due", () => {
    expect(loanRegisterDpd(live("2026-10-07"), JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "not-due" });
    expect(loanRegisterDpd(live("2027-01-01"), JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "not-due" });
  });

  it("reads only the calendar day of a timestamped due date", () => {
    expect(loanRegisterDpd(live("2026-10-06T00:00:00"), JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "due-today" });
  });

  it("a closed loan is never due, whatever its due date", () => {
    for (const status of ["CLOSED", "REPAID"]) {
      expect(loanRegisterDpd({ ...live("2026-10-06"), status }, JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "none" });
      expect(loanRegisterDpd({ ...live("2026-11-01"), status }, JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "none" });
    }
    expect(
      loanRegisterDpd({ ...live("2026-11-01"), closedOn: "2026-10-02" }, JUST_AFTER_IST_MIDNIGHT),
    ).toEqual({ kind: "none" });
  });

  it("no usable due date reads as none", () => {
    expect(loanRegisterDpd(live(null), JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "none" });
    expect(loanRegisterDpd(live(""), JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "none" });
    expect(loanRegisterDpd(live("06/10/2026"), JUST_AFTER_IST_MIDNIGHT)).toEqual({ kind: "none" });
  });
});

describe("loanRegisterDpdLabel", () => {
  it("labels every state", () => {
    expect(loanRegisterDpdLabel({ kind: "overdue", days: 12 })).toBe("12d");
    expect(loanRegisterDpdLabel({ kind: "due-today" })).toBe("due today");
    expect(loanRegisterDpdLabel({ kind: "not-due" })).toBe("not due");
    expect(loanRegisterDpdLabel({ kind: "none" })).toBe("—");
  });
});
