import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DocCard } from "@/components/staff/doc-card";

describe("DocCard", () => {
  it("disables View document while busy", () => {
    render(<DocCard n={1} type="PAN" date="d" onView={() => {}} busy />);
    expect(screen.getByRole("button", { name: /View document/ })).toBeDisabled();
  });
  it("is enabled by default", () => {
    render(<DocCard n={1} type="PAN" date="d" onView={() => {}} />);
    expect(screen.getByRole("button", { name: /View document/ })).toBeEnabled();
  });
});
