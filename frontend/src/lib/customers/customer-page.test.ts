import { describe, expect, it } from "vitest";
import { customerPageHref } from "./customer-page";

describe("customerPageHref", () => {
  it("links a customer modal action to that customer's full staff profile", () => {
    expect(customerPageHref(42)).toBe("/staff/customers/42");
  });
});
