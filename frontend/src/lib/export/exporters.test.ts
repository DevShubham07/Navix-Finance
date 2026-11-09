import { afterEach, describe, expect, it, vi } from "vitest";
import type { jsPDF } from "jspdf";
import { csvCell, drawPdfFooter, exportCsvText } from "@/lib/export/exporters";
import { setExportSimulation } from "@/lib/export/simulation-mode";

describe("csvCell", () => {
  it("neutralises cells a spreadsheet would run as a formula", () => {
    expect(csvCell("=HYPERLINK(\"http://x\",\"y\")")).toBe("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"");
    expect(csvCell("@SUM(A1)")).toBe("'@SUM(A1)");
    expect(csvCell("+cmd|' /C calc'!A0")).toBe("'+cmd|' /C calc'!A0");
    expect(csvCell("-2+3")).toBe("'-2+3");
  });

  it("leaves ordinary values and real negative numbers alone", () => {
    expect(csvCell("-1250.50")).toBe("-1250.50");
    expect(csvCell(-5)).toBe("-5");
    expect(csvCell("12 MG Road, Pune")).toBe("\"12 MG Road, Pune\"");
    expect(csvCell("—")).toBe("—");
    expect(csvCell(null)).toBe("");
  });
});

function captureDownload() {
  const anchor = { href: "", download: "", click: vi.fn(), remove: vi.fn() };
  vi.stubGlobal("document", { createElement: () => anchor, body: { appendChild: vi.fn() } });
  let blob: Blob | undefined;
  vi.spyOn(URL, "createObjectURL").mockImplementation((b) => {
    blob = b as Blob;
    return "blob:x";
  });
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => undefined);
  return { anchor, text: () => blob!.text() };
}

function fakeDoc() {
  return {
    internal: { pageSize: { getWidth: () => 800, getHeight: () => 600 } },
    getNumberOfPages: () => 1,
    setPage: vi.fn(),
    setFont: vi.fn(),
    setFontSize: vi.fn(),
    setTextColor: vi.fn(),
    text: vi.fn(),
  };
}

const footerText = (d: ReturnType<typeof fakeDoc>) => d.text.mock.calls.map((c) => String(c[0]));

describe("simulation labels", () => {
  afterEach(() => {
    setExportSimulation(false);
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("labels a CSV and its filename in simulation mode", async () => {
    setExportSimulation(true);
    const d = captureDownload();
    exportCsvText("loans", "a,b\n1,2");
    expect(d.anchor.download).toBe("SIMULATION-loans.csv");
    expect(await d.text()).toContain("SIMULATION - NOT REAL DATA\na,b");
  });

  it("leaves a normal CSV unchanged", async () => {
    const d = captureDownload();
    exportCsvText("loans", "a,b\n1,2");
    expect(d.anchor.download).toBe("loans.csv");
    expect(await d.text()).not.toContain("SIMULATION");
  });

  it("labels every PDF page in simulation mode", () => {
    setExportSimulation(true);
    const d = fakeDoc();
    drawPdfFooter(d as unknown as jsPDF);
    expect(footerText(d)).toContain("SIMULATION - NOT REAL DATA");
  });

  it("does not label a normal PDF", () => {
    const d = fakeDoc();
    drawPdfFooter(d as unknown as jsPDF);
    expect(footerText(d)).not.toContain("SIMULATION - NOT REAL DATA");
  });
});
