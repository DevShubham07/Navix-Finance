import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SimulationBanner } from "./simulation-banner";

describe("SimulationBanner", () => {
  it("states that the console is a simulation with no way to dismiss it", () => {
    render(<SimulationBanner />);
    expect(screen.getByRole("status")).toHaveTextContent("SIMULATION");
    expect(screen.getByRole("status")).toHaveTextContent("not real company performance");
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
