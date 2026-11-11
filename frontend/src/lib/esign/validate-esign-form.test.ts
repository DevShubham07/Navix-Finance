import { describe, expect, it } from "vitest";
import { MAX_SIGNERS, newSigner, validateEsignForm, type EsignFormState } from "./validate-esign-form";

const BOX = { page: 1, x: 10, y: 10, width: 160, height: 70 };

const ok = (over: Partial<EsignFormState> = {}): EsignFormState => ({
  title: "Vendor agreement",
  hasFile: true,
  sendEmail: true,
  signers: [{ ...newSigner(0), name: "Asha Rao", email: "asha@example.com", boxes: [BOX] }],
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

  it("requires at least one signature box per signer", () => {
    const f = ok();
    f.signers[0].boxes = [];
    expect(validateEsignForm(f).join()).toContain("at least one signature box");
  });

  it("rejects a box under the minimum size and more than 50 boxes", () => {
    const f = ok();
    f.signers[0].boxes = [{ ...BOX, width: 100 }];
    expect(validateEsignForm(f).join()).toContain("smaller than");
    f.signers[0].boxes = Array.from({ length: 51 }, () => BOX);
    expect(validateEsignForm(f).join()).toContain("at most 50");
  });

  it("caps signers at the maximum", () => {
    const signers = Array.from({ length: MAX_SIGNERS + 1 }, (_, i) => ({
      ...newSigner(i),
      name: "N",
      email: "a@b.co",
      boxes: [BOX],
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
});
