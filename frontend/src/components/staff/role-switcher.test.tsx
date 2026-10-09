import { fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RoleSwitcher } from "@/components/staff/role-switcher";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));
const success = vi.fn();
vi.mock("@/components/ui", () => ({ toast: { success: (m: string) => success(m) } }));

const wrap = (ui: React.ReactElement) =>
  render(<QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>);

describe("RoleSwitcher", () => {
  beforeEach(() => {
    localStorage.clear();
    push.mockClear();
    success.mockClear();
  });

  it("ADMIN: pill lists the 7 working roles, checks the current, switches and persists", () => {
    wrap(<RoleSwitcher staffId="9" realRole="ADMIN" role="CREDIT_HEAD" />);
    const pill = screen.getByRole("button", { name: "Switch role, currently Credit Head" });
    expect(pill).toHaveAttribute("aria-expanded", "false");
    fireEvent.click(pill);

    const items = screen.getAllByRole("menuitemradio");
    expect(items).toHaveLength(7);
    expect(screen.getByText("Assign leads and monitor your team")).toBeInTheDocument();
    expect(items.filter((i) => i.getAttribute("aria-checked") === "true")).toHaveLength(1);
    expect(screen.getByRole("menuitemradio", { name: /Credit Head/ })).toHaveAttribute("aria-checked", "true");

    fireEvent.click(screen.getByRole("menuitemradio", { name: /Credit Executive/ }));
    expect(localStorage.getItem("navix-staff-working-role:9")).toBe("CREDIT_EXECUTIVE");
    expect(push).toHaveBeenCalledWith("/staff/dashboard");
    expect(success).toHaveBeenCalledWith("Now working as Credit Executive");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("selecting the current role only closes", () => {
    wrap(<RoleSwitcher staffId="9" realRole="ADMIN" role="CREDIT_HEAD" />);
    fireEvent.click(screen.getByRole("button", { name: /Switch role/ }));
    fireEvent.click(screen.getByRole("menuitemradio", { name: /Credit Head/ }));
    expect(push).not.toHaveBeenCalled();
    expect(localStorage.getItem("navix-staff-working-role:9")).toBeNull();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("Escape closes the menu and returns focus to the pill", () => {
    wrap(<RoleSwitcher staffId="9" realRole="ADMIN" role="CREDIT_HEAD" />);
    const pill = screen.getByRole("button", { name: /Switch role/ });
    fireEvent.click(pill);
    fireEvent.keyDown(screen.getByRole("menu"), { key: "Escape" });
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(pill).toHaveFocus();
  });

  it("single-role staff get a static pill with no button", () => {
    wrap(<RoleSwitcher staffId="3" realRole="CREDIT_EXECUTIVE" role="CREDIT_EXECUTIVE" />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(screen.getByText("Credit Executive")).toBeInTheDocument();
  });
});
