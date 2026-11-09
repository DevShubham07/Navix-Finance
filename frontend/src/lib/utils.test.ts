import { describe, expect, it } from "vitest";
import { formatDate, formatDateTime, normalizeMobile } from "./utils";
import { istDay } from "./staff/column-filter-match";

describe("normalizeMobile", () => {
  it.each([
    ["+917417682036", "7417682036"],
    ["07417682036", "7417682036"],
    ["+91 74176 82036", "7417682036"],
    ["74176-82036", "7417682036"],
    ["7417682036", "7417682036"],
  ])("normalizes %s", (input, expected) => {
    expect(normalizeMobile(input)).toBe(expected);
  });
});

/**
 * Dates are IST business days, whatever the viewer's machine says.
 *
 * These ran green before the `timeZone` was pinned ONLY because the runner happens to sit in IST;
 * the `TZ=UTC` case below is the one that fails without it, and UTC is what CI and a deployed
 * browser abroad both give you.
 */
describe("formatDate / formatDateTime are IST", () => {
  const LATE_IST = "2026-03-04T20:30:00Z"; // 02:00 on the 5th in Asia/Kolkata

  it("names the IST day for a timestamp that falls on the previous UTC day", () => {
    expect(formatDate(LATE_IST)).toBe("05 Mar 2026");
  });

  it("agrees with the date filter's own IST day", () => {
    // The register's filter compares `istDay`; if these two disagreed, a row would sit outside a
    // range that visibly contains the date printed in its own cell.
    expect(istDay(LATE_IST)).toBe("2026-03-05");
  });

  it("renders the time in IST too, so an audit trail reads as the office saw it", () => {
    expect(formatDateTime(LATE_IST)).toMatch(/05 Mar 2026.*02:00\s*am/i);
  });
});
