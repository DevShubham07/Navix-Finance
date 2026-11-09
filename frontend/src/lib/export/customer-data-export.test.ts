import { afterEach, describe, expect, it, vi } from "vitest";

const rowsMock = vi.hoisted(() => vi.fn());
const saved = vi.hoisted(() => [] as Array<{ getNumberOfPages(): number }>);
vi.mock("@/lib/api/customer-export", () => ({ customerExportApi: { rows: rowsMock } }));
// Real jsPDF and autoTable lay the document out; only the download (save) is replaced, to hand the document back.
vi.mock("jspdf", async (importOriginal) => {
  const mod = await importOriginal<typeof import("jspdf")>();
  class Capturing extends mod.jsPDF {
    constructor(...args: ConstructorParameters<typeof mod.jsPDF>) {
      super(...args);
      // jsPDF installs save() on each instance, so it is replaced on the instance.
      (this as unknown as { save: () => unknown }).save = () => saved.push(this);
    }
  }
  return { ...mod, jsPDF: Capturing };
});

import type { ExportColumn, ExportField } from "@/lib/api/customer-export";
import { csvCell, csvTextNumberCell, exportCustomerCardsPdf, exportCustomerTablePdf } from "@/lib/export/exporters";
import {
  PAGE_SIZE,
  PDF_MAX_ROWS,
  PDF_TABLE_MAX_FIELDS,
  buildCards,
  csvDataCell,
  csvHeader,
  csvLine,
  displayCell,
  fetchRowPages,
  pdfLayout,
} from "@/lib/export/customer-data-export";

afterEach(() => {
  vi.restoreAllMocks();
  rowsMock.mockReset();
});

describe("digit-only identifiers in CSV", () => {
  it("writes an Aadhaar number as =\"…\" so Excel keeps all twelve digits", () => {
    expect(csvTextNumberCell("123456789012")).toBe('"=""123456789012"""');
    expect(csvDataCell("TEXTNUM", "123456789012")).toBe('"=""123456789012"""');
  });

  it("keeps leading zeros on an account number", () => {
    expect(csvDataCell("TEXTNUM", "0012345678")).toBe('"=""0012345678"""');
  });

  it("does not weaken the formula guard for anything that is not all digits", () => {
    expect(csvDataCell("TEXTNUM", "=1+1")).toBe("'=1+1");
    expect(csvDataCell("TEXTNUM", "12345 678")).toBe("12345 678");
    expect(csvDataCell("TEXTNUM", "12=34")).toBe("12=34");
    expect(csvDataCell("TEXT", "=HYPERLINK(1)")).toBe("'=HYPERLINK(1)");
    expect(csvDataCell("TEXT", "123456789012")).toBe("123456789012");
    expect(csvCell("=1+1")).toBe("'=1+1");
  });

  it("writes money as plain rupees from paise, and empty cells as nothing", () => {
    expect(csvDataCell("MONEY", 1250050)).toBe("12500.50");
    expect(csvDataCell("MONEY", null)).toBe("");
    expect(csvDataCell("BOOL", true)).toBe("Yes");
    expect(csvDataCell("DATE", "2026-11-09")).toBe("2026-11-09");
  });

  it("builds a header and a row in column order", () => {
    const cols: ExportColumn[] = [
      { key: "full_name", label: "Full name", type: "TEXT" },
      { key: "aadhaar", label: "Aadhaar number", type: "TEXTNUM" },
      { key: "outstanding_live", label: "Outstanding", type: "MONEY" },
    ];
    expect(csvHeader(cols)).toBe("Full name,Aadhaar number,Outstanding (Rs.)");
    expect(csvLine(cols, ["Asha, K", "123456789012", 254000])).toBe('"Asha, K","=""123456789012""",2540.00');
  });
});

describe("display formatting", () => {
  it("shows paise as rupees and dates as real dates", () => {
    expect(displayCell("MONEY", 2540000)).toBe("₹25,400.00");
    expect(displayCell("DATE", "2026-11-09")).toBe("09 Nov 2026");
    expect(displayCell("DATETIME", "2026-11-09 03:52")).toBe("09 Nov 2026, 03:52");
    expect(displayCell("BOOL", false)).toBe("No");
    expect(displayCell("TEXT", null)).toBe("");
  });
});

describe("PDF layout", () => {
  it("is a table for 12 fields or fewer and cards above that", () => {
    expect(PDF_TABLE_MAX_FIELDS).toBe(12);
    expect(pdfLayout(1)).toBe("table");
    expect(pdfLayout(12)).toBe("table");
    expect(pdfLayout(13)).toBe("cards");
    expect(pdfLayout(190)).toBe("cards");
  });

  it("caps a PDF at 500 customers", () => {
    expect(PDF_MAX_ROWS).toBe(500);
  });

  const fields: ExportField[] = [
    { key: "customer_id", label: "Customer ID", group: "Personal", type: "NUMBER", sensitive: false },
    { key: "full_name", label: "Full name", group: "Personal", type: "TEXT", sensitive: false },
    { key: "monthly_salary", label: "Monthly salary", group: "Employment and salary", type: "MONEY", sensitive: false },
    { key: "employer", label: "Employer", group: "Employment and salary", type: "TEXT", sensitive: false },
  ];
  const columns: ExportColumn[] = fields.map((f) => ({ key: f.key, label: f.label, type: f.type }));

  it("groups a card's fields under their group and leaves empty values out", () => {
    const [card] = buildCards(columns, [[1042, "Sample Customer A", 6200000, null]], fields);
    expect(card.title).toBe("#1042  Sample Customer A");
    expect(card.sections.map((s) => s.title)).toEqual(["Personal", "Employment and salary"]);
    expect(card.sections[1].rows).toEqual([["Monthly salary", "₹62,000.00"]]);
  });

  function capture() {
    saved.length = 0;
    return saved;
  }
  const actor = { name: "Admin", role: "Admin" };

  it("writes the table PDF without throwing, with long values wrapped", () => {
    const docs = capture();
    const header = Array.from({ length: 12 }, (_, i) => `Column ${i + 1}`);
    const long = "A very long value that must wrap rather than run off the edge of the page ".repeat(6);
    exportCustomerTablePdf({
      fileBase: "t",
      title: "DhanBoost customer data export",
      subtitle: "Overdue loans",
      summary: "Overdue loans, 1–7 days past due. Came between 01 Nov 2026 and 09 Nov 2026.",
      header,
      rows: Array.from({ length: 40 }, () => header.map(() => long)),
      actor,
      generatedAt: "now",
    });
    expect(docs).toHaveLength(1);
    expect(docs[0].getNumberOfPages()).toBeGreaterThan(1);
  });

  it("writes the cards PDF without throwing", () => {
    const docs = capture();
    exportCustomerCardsPdf({
      fileBase: "c",
      title: "DhanBoost customer data export",
      subtitle: "Overdue loans",
      summary: "Overdue loans.",
      cards: Array.from({ length: 30 }, (_, n) => ({
        title: `#${n}  Sample`,
        badge: "ACTIVE",
        sections: [
          { title: "Personal", rows: [["Address", "12 MG Road, ".repeat(30)] as [string, string], ["Mobile", "9000000012"] as [string, string]] },
        ],
      })),
      actor,
      generatedAt: "now",
    });
    expect(docs).toHaveLength(1);
    expect(docs[0].getNumberOfPages()).toBeGreaterThan(0);
  });
});

describe("fetching pages", () => {
  const ids = Array.from({ length: 450 }, (_, i) => i + 1);
  const base = { grain: "customer" as const, ids, fields: ["full_name"], includeIdNumbers: false };

  it("asks for 200 ids at a time, one request at a time", async () => {
    let inFlight = 0;
    let maxInFlight = 0;
    const sizes: number[] = [];
    rowsMock.mockImplementation(async (req: { ids: number[] }) => {
      inFlight++;
      maxInFlight = Math.max(maxInFlight, inFlight);
      sizes.push(req.ids.length);
      await Promise.resolve();
      inFlight--;
      return { columns: [], rows: req.ids.map((id) => [id]) };
    });
    const progress: number[] = [];
    let rows = 0;
    await fetchRowPages({ ...base, onProgress: (d) => progress.push(d) }, (p) => (rows += p.rows.length));
    expect(PAGE_SIZE).toBe(200);
    expect(sizes).toEqual([200, 200, 50]);
    expect(maxInFlight).toBe(1);
    expect(progress).toEqual([200, 400, 450]);
    expect(rows).toBe(450);
  });

  it("stops when cancelled and fetches nothing more", async () => {
    const ctrl = new AbortController();
    rowsMock.mockImplementation(async () => {
      ctrl.abort();
      return { columns: [], rows: [] };
    });
    await expect(fetchRowPages({ ...base, signal: ctrl.signal }, () => undefined)).rejects.toMatchObject({ name: "AbortError" });
    expect(rowsMock).toHaveBeenCalledTimes(1);
  });
});
