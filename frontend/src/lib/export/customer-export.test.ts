import { describe, expect, it } from "vitest";
import type { CustomerDetail } from "@/lib/api/applications";
import {
  buildCustomerCsvSections,
  buildCustomerPdf,
  customerCsvText,
  customerExportFileBase,
  humanise,
  rupees,
  type CustomerExportExtras,
} from "@/lib/export/customer-export";

const detail: CustomerDetail = {
  customerId: 5914823,
  ownerName: "Meera Krishnan",
  limitOverridePaise: 7_500_000,
  profile: {
    applicationId: 318,
    fullName: "Asha Verma",
    pan: "ABCDE1234F",
    aadhaar: "234567890123",
    mobile: "9876543210",
    dob: "1991-04-12",
    // Borrower-typed text a spreadsheet would run as a formula.
    address: "=HYPERLINK(\"http://x\",\"12 MG Road\")",
    employer: "Acme Ltd",
    employmentStatus: "SALARIED",
    monthlySalaryPaise: 4_200_000,
    salaryBank: "HDFC",
    panVerified: true,
    pennyDropVerified: false,
  },
  applications: [
    {
      id: 318,
      customerId: 5914823,
      status: "ACTIVE",
      amountRequestedPaise: 1_000_000,
      eligibleLimitPaise: 1_050_000,
      sanctionedAmountPaise: 1_000_000,
      approvedRepaymentDate: "2026-06-30",
      sanctionTenureDays: 27,
      salaryCreditDay: 30,
      purpose: "Medical",
      assignedExecutiveId: 7,
      assignedExecutiveName: "Ravi Iyer",
      loanId: 91,
      createdAt: "2026-06-01T04:30:00Z",
      // Must never reach the file.
      disbursalAccountNumber: "00112233445566",
      disbursalIfsc: "HDFC0000123",
    },
  ],
  loans: [
    {
      id: 91,
      customerId: 5914823,
      principalPaise: 1_000_000,
      processingFeePaise: 100_000,
      gstPaise: 18_000,
      netDisbursedPaise: 882_000,
      dailyInterestRate: 0.01,
      disbursedOn: "2026-06-03",
      dueDate: "2026-06-30",
      totalRepayablePaise: 1_270_000,
      outstandingPaise: 0,
      status: "CLOSED",
      disbursalTxnRef: "UTR123",
      closedOn: "2026-06-30",
    },
  ],
  payments: [
    {
      id: 5,
      loanId: 91,
      amountPaise: 1_270_000,
      method: "UPI",
      status: "VERIFIED",
      txnRef: "-UPI99",
      proofUrl: null,
      paidOn: "2026-06-30",
      partial: false,
    },
  ],
  outstandingByLoanId: {
    "91": { loanId: 91, asOf: "2026-06-30", outstandingPaise: 0, interestPaise: 270_000, penaltyPaise: 0, verifiedPaise: 1_270_000 },
  },
};

const extras: CustomerExportExtras = {
  documents: [
    {
      applicationId: 318,
      applicationStatus: "ACTIVE",
      documents: [
        { id: 1, docType: "AADHAAR_CARD_FRONT", fileName: "front.jpg", contentType: "image/jpeg", sizeBytes: 100, uploadedAt: "2026-06-01T05:00:00Z" },
      ],
    },
  ],
  verifications: [
    { checkType: "PENNY_DROP", status: "FAIL", message: "Name mismatch", derived: {}, provider: "SIGNZY", checkedAt: "2026-06-02T09:15:00Z" },
  ],
  references: [{ slot: 1, fullName: "Rohan Verma", mobile: "9000000001", relation: "SIBLING" }],
};

const sectionsByTitle = () => Object.fromEntries(buildCustomerCsvSections(detail, extras).map((s) => [s.title, s]));

describe("buildCustomerCsvSections", () => {
  it("produces every section, in order", () => {
    expect(buildCustomerCsvSections(detail, extras).map((s) => s.title)).toEqual([
      "Customer profile",
      "Applications",
      "Loans",
      "Repayments",
      "Verifications",
      "References",
      "Documents",
    ]);
  });

  it("writes the profile as Field/Value rows with full PAN and Aadhaar and formatted money", () => {
    const rows = sectionsByTitle()["Customer profile"].rows;
    const byField = Object.fromEntries(rows.map(([f, v]) => [f, v]));
    expect(byField["Customer ID"]).toBe(5914823);
    expect(byField["Full name"]).toBe("Asha Verma");
    expect(byField["PAN"]).toBe("ABCDE1234F");
    expect(byField["Aadhaar"]).toBe("234567890123");
    expect(byField["Monthly salary (Rs.)"]).toBe("42000.00");
    expect(byField["Admin limit override (Rs.)"]).toBe("75000.00");
    expect(byField["Date of birth"]).toBe("12 Apr 1991");
    expect(byField["PAN verified"]).toBe("Yes");
    expect(byField["Penny drop verified"]).toBe("No");
    expect(byField["Owner"]).toBe("Meera Krishnan");
  });

  it("carries the loan's closure date beside its dates and itemised balance", () => {
    const { header, rows } = sectionsByTitle()["Loans"];
    const loan = Object.fromEntries(header.map((h, i) => [h, rows[0][i]]));
    expect(loan["Loan #"]).toBe(91);
    expect(loan["Disbursed on"]).toBe("03 Jun 2026");
    expect(loan["Due date"]).toBe("30 Jun 2026");
    expect(loan["Closure date"]).toBe("30 Jun 2026");
    expect(loan["Net disbursed (Rs.)"]).toBe("8820.00");
    expect(loan["Total repayable (Rs.)"]).toBe("12700.00");
    expect(loan["Interest accrued (Rs.)"]).toBe("2700.00");
    expect(loan["Paid (verified) (Rs.)"]).toBe("12700.00");
    expect(loan["Outstanding (Rs.)"]).toBe("0.00");
    expect(loan["Status"]).toBe("Closed");
    expect(loan["Txn ref"]).toBe("UTR123");
  });

  it("leaves the closure date blank on a loan that is still running", () => {
    const open = { ...detail, loans: [{ ...detail.loans[0], status: "ACTIVE", closedOn: null }] };
    const { header, rows } = buildCustomerCsvSections(open, extras).find((s) => s.title === "Loans")!;
    expect(rows[0][header.indexOf("Closure date")]).toBe("");
  });

  it("humanises enum codes in applications, repayments, verifications, references and documents", () => {
    const s = sectionsByTitle();
    expect(s["Applications"].rows[0]).toContain("Active");
    expect(s["Applications"].rows[0]).toContain("Ravi Iyer");
    expect(s["Repayments"].rows[0]).toEqual([5, 91, "30 Jun 2026", "12700.00", "UPI", "Verified", "-UPI99", ""]);
    expect(s["Verifications"].rows[0]).toEqual(["Penny drop", "Fail", "SIGNZY", expect.stringMatching(/^02 Jun 2026/), "Name mismatch"]);
    expect(s["References"].rows[0]).toEqual([1, "Rohan Verma", "9000000001", "Sibling"]);
    expect(s["Documents"].rows[0]).toEqual(["Aadhaar card front", "front.jpg", expect.stringMatching(/^01 Jun 2026/), 318]);
  });

  it("never writes the disbursal account", () => {
    const text = customerCsvText(buildCustomerCsvSections(detail, extras));
    expect(text).not.toContain("00112233445566");
    expect(text).not.toContain("HDFC0000123");
  });
});

describe("customerCsvText", () => {
  it("separates sections with a blank line, each headed by its title row", () => {
    const text = customerCsvText(buildCustomerCsvSections(detail, extras));
    expect(text.startsWith("Customer profile\nField,Value\nCustomer ID,5914823\n")).toBe(true);
    expect(text).toContain("\n\nLoans\nLoan #,Principal (Rs.),");
    expect(text).toContain("\n\nDocuments\nType,File name,Uploaded,Application #\n");
  });

  it("neutralises formula cells but leaves real negative numbers alone", () => {
    const text = customerCsvText(buildCustomerCsvSections(detail, extras));
    expect(text).toContain("Address,\"'=HYPERLINK(\"\"http://x\"\",\"\"12 MG Road\"\")\"");
    expect(text).toContain(",'-UPI99,");
    expect(customerCsvText([{ title: "T", header: ["N"], rows: [["-1250.50"]] }])).toBe("T\nN\n-1250.50");
  });
});

describe("buildCustomerPdf", () => {
  it("titles the document after the customer and reuses the CSV sections", () => {
    const pdf = buildCustomerPdf(detail, extras);
    expect(pdf.title).toBe("Customer profile — Asha Verma (#5914823)");
    expect(pdf.subtitle).toBe("Latest application #318 · Active");
    expect(pdf.sections).toEqual(buildCustomerCsvSections(detail, extras));
  });

  it("falls back to the id when the profile has no name", () => {
    expect(buildCustomerPdf({ ...detail, profile: null, applications: [] }, extras)).toMatchObject({
      title: "Customer profile — Customer #5914823 (#5914823)",
      subtitle: "No applications yet",
    });
  });
});

describe("helpers", () => {
  it("formats paise as rupees with two decimals", () => {
    expect(rupees(1_270_000)).toBe("12700.00");
    expect(rupees(5)).toBe("0.05");
    expect(rupees(null)).toBe("");
  });

  it("humanises codes while keeping acronyms", () => {
    expect(humanise("PAN_CARD_FRONT")).toBe("PAN card front");
    expect(humanise("DIGILOCKER")).toBe("DigiLocker");
    expect(humanise("PENDING_VERIFICATION")).toBe("Pending verification");
    expect(humanise(null)).toBe("");
  });

  it("names the file after the customer id and the IST calendar day", () => {
    expect(customerExportFileBase(5914823, new Date("2026-10-17T20:30:00Z"))).toBe("customer-5914823-20261018");
    expect(customerExportFileBase(42, new Date("2026-10-17T10:00:00Z"))).toBe("customer-42-20261017");
  });
});
