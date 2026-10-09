import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Field, FieldGrid } from "@/components/ui/field-grid";
import { Section } from "@/components/staff/detail-parts";

describe("Field keyLabel", () => {
  it("renders a blue label only when keyLabel is set", () => {
    render(<FieldGrid cols={2}><Field label="Loan number" keyLabel>#1</Field><Field label="Purpose">x</Field></FieldGrid>);
    expect(screen.getByText("Loan number")).toHaveClass("text-info-500");
    expect(screen.getByText("Purpose")).toHaveClass("text-black");
  });
});

describe("Section tone", () => {
  it("tints header and border by meaning, neutral by default", () => {
    const { container, rerender } = render(<Section title="Decision" tone="error">x</Section>);
    expect(container.firstChild).toHaveClass("border-error-100");
    expect(screen.getByText("Decision").parentElement).toHaveClass("bg-error-50");
    rerender(<Section title="Decision">x</Section>);
    expect(container.firstChild).toHaveClass("border-line");
    expect(screen.getByText("Decision").parentElement).not.toHaveClass("bg-error-50");
  });
});
