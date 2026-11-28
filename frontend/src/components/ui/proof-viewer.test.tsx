import { describe, expect, it, vi } from "vitest";
import * as React from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ProofFilePicker, ProofViewer, proofKindFromUrl } from "./proof-viewer";

describe("proofKindFromUrl", () => {
  it("reads the extension from the path, not the presign query", () => {
    expect(proofKindFromUrl("https://b.s3.ap-south-1.amazonaws.com/loan/repayment-proof/x-r.pdf?X-Amz-Signature=a.png")).toBe("pdf");
    expect(proofKindFromUrl("https://b.s3.ap-south-1.amazonaws.com/loan/repayment-proof/x-shot.png?X-Amz=1.pdf")).toBe("image");
  });
});

describe("<ProofViewer/>", () => {
  it("zooms in, out and resets", async () => {
    render(<ProofViewer src="https://x/shot.png" open onClose={() => {}} />);
    expect(screen.getByText("100%")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "Zoom in" }));
    expect(screen.getByText("125%")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "Zoom out" }));
    await userEvent.click(screen.getByRole("button", { name: "Zoom out" }));
    expect(screen.getByText("80%")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "Reset view" }));
    expect(screen.getByText("100%")).toBeTruthy();
  });

  it("renders the checker's footer", () => {
    render(<ProofViewer src="https://x/shot.png" open onClose={() => {}} footer={<button type="button">Validate</button>} />);
    expect(screen.getByRole("button", { name: "Validate" })).toBeTruthy();
  });
});

describe("<ProofFilePicker/>", () => {
  it("shows the picked file with a preview before anything is uploaded", async () => {
    const createObjectURL = vi.fn(() => "blob:preview");
    Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() });
    function Harness() {
      const [file, setFile] = React.useState<File | null>(null);
      return <ProofFilePicker label="Screenshot" file={file} onChange={setFile} />;
    }
    render(<Harness />);
    await userEvent.upload(screen.getByLabelText("Screenshot"), new File(["x"], "utr.png", { type: "image/png" }));
    expect(screen.getByText("utr.png")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: /^Preview$/ }));
    expect(screen.getByRole("dialog", { name: "Preview — utr.png" })).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "Close" }));
    await userEvent.click(screen.getByRole("button", { name: "Remove the attached screenshot" }));
    expect(screen.queryByText("utr.png")).toBeNull();
  });
});
