import { describe, expect, it } from "vitest";
import { mapLeadCsv, parseCsv } from "./parse-csv";

describe("parseCsv", () => {
  it("parses a happy-path CSV", () => {
    const text =
      "name,contact number,pan card,pincode,emailid\nRavi Kumar,9876543210,ABCDE1234F,560001,ravi@example.com\n";
    expect(parseCsv(text)).toEqual([
      ["name", "contact number", "pan card", "pincode", "emailid"],
      ["Ravi Kumar", "9876543210", "ABCDE1234F", "560001", "ravi@example.com"],
    ]);
  });

  it("handles a quoted comma inside a field", () => {
    const text = 'name,contact number\n"Kumar, Ravi",9876543210\n';
    expect(parseCsv(text)).toEqual([
      ["name", "contact number"],
      ["Kumar, Ravi", "9876543210"],
    ]);
  });

  it("strips a leading BOM", () => {
    const text = "﻿name,contact number\nRavi,9876543210\n";
    expect(parseCsv(text)[0]).toEqual(["name", "contact number"]);
  });
});

describe("mapLeadCsv", () => {
  it("maps a happy-path file", () => {
    const rows = parseCsv(
      "name,contact number,pan card,pincode,emailid\nRavi Kumar,9876543210,ABCDE1234F,560001,ravi@example.com\n",
    );
    const { rows: mapped, issues } = mapLeadCsv(rows);
    expect(issues).toEqual([]);
    expect(mapped).toEqual([
      {
        name: "Ravi Kumar",
        mobile: "9876543210",
        pan: "ABCDE1234F",
        pincode: "560001",
        email: "ravi@example.com",
      },
    ]);
  });

  it("flags a missing required header column", () => {
    const rows = parseCsv("foo,bar\n1,2\n");
    const { rows: mapped, issues } = mapLeadCsv(rows);
    expect(mapped).toEqual([]);
    expect(issues.length).toBeGreaterThan(0);
    expect(issues.some((i) => i.field === "name" || i.field === "contact number")).toBe(true);
  });

  it("flags a row whose column count differs from the header", () => {
    const rows = parseCsv("name,contact number\nRavi,9876543210,extra\n");
    const { issues } = mapLeadCsv(rows);
    expect(issues).toEqual([{ row: 1, field: "row", message: "Expected 2 columns, found 3." }]);
  });

  it("matches aliased headers case/whitespace/underscore-insensitively", () => {
    const rows = parseCsv(
      "Name,Mobile_Number,PAN Number,Pin Code,E-Mail\nRavi,9876543210,ABCDE1234F,560001,ravi@example.com\n",
    );
    const { rows: mapped, issues } = mapLeadCsv(rows);
    expect(issues).toEqual([]);
    expect(mapped[0]).toEqual({
      name: "Ravi",
      mobile: "9876543210",
      pan: "ABCDE1234F",
      pincode: "560001",
      email: "ravi@example.com",
    });
  });

  it("normalizes a +91-prefixed, hyphenated mobile number", () => {
    const rows = parseCsv("name,contact number\nRavi,+91 98765-43210\n");
    const { rows: mapped, issues } = mapLeadCsv(rows);
    expect(issues).toEqual([]);
    expect(mapped[0].mobile).toBe("9876543210");
  });

  it("flags an 11-digit mobile number", () => {
    const rows = parseCsv("name,contact number\nRavi,98765432109\n");
    const { issues } = mapLeadCsv(rows);
    expect(issues).toEqual([
      { row: 1, field: "mobile", message: "must be a valid 10-digit mobile number" },
    ]);
  });

  it("flags a mobile number starting with 5", () => {
    const rows = parseCsv("name,contact number\nRavi,5876543210\n");
    const { issues } = mapLeadCsv(rows);
    expect(issues).toEqual([
      { row: 1, field: "mobile", message: "must be a valid 10-digit mobile number" },
    ]);
  });
});
