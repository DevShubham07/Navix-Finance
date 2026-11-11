import { describe, expect, it } from "vitest";
import {
  MAX_SIGNERS,
  isValidPageList,
  newSigner,
  pagesValue,
  validateEsignForm,
  type EsignFormState,
} from "./validate-esign-form";

const ok = (over: Partial<EsignFormState> = {}): EsignFormState => ({
  title: "Vendor agreement",
  hasFile: true,
  sendEmail: true,
  signers: [{ ...newSigner(0), name: "Asha Rao", email: "asha@example.com" }],
  ...over,
});

describe("validateEsignForm", () => {
  it("accepts a valid form", () => expect(validateEsignForm(ok())).toEqual([]));

  it("requires file and title", () => {
    expect(validateEsignForm(ok({ hasFile: false, title: " " }))).toHaveLength(2);
  });

  it("requires a signer name", () => {
    const f = ok();
    f.signers[0].name = "  ";
    expect(validateEsignForm(f)).toHaveLength(1);
  });

  it("caps signers at the maximum", () => {
    const signers = Array.from({ length: MAX_SIGNERS + 1 }, (_, i) => ({
      ...newSigner(i),
      name: "N",
      email: "a@b.co",
    }));
    expect(validateEsignForm(ok({ signers })).join()).toContain("At most");
  });

  it("needs an email for non-self signers only when emailing", () => {
    const f = ok();
    f.signers[0].email = "";
    expect(validateEsignForm(f)).toHaveLength(1);
    expect(validateEsignForm({ ...f, sendEmail: false })).toEqual([]);
    f.signers[0].self = true;
    expect(validateEsignForm(f)).toEqual([]);
  });

  it("rejects a malformed email", () => {
    const f = ok();
    f.signers[0].email = "nope";
    expect(validateEsignForm(f)).toHaveLength(1);
  });

  it("validates custom page lists", () => {
    expect(isValidPageList("1,3")).toBe(true);
    expect(isValidPageList("2, 5")).toBe(true);
    for (const bad of ["", "0", "a", "1,,2", "1,1", "-1", "1;2"]) expect(isValidPageList(bad)).toBe(false);
    const f = ok();
    f.signers[0].pagesMode = "Custom";
    f.signers[0].pagesCustom = "x";
    expect(validateEsignForm(f)).toHaveLength(1);
  });

  it("builds the pages value and staggers positions", () => {
    const s = { ...newSigner(1), pagesMode: "Custom" as const, pagesCustom: "1, 3" };
    expect(pagesValue(s)).toBe("1,3");
    expect(pagesValue(newSigner(0))).toBe("All");
    expect(newSigner(0).position).toBe("BottomLeft");
    expect(newSigner(1).position).toBe("BottomRight");
  });
});
