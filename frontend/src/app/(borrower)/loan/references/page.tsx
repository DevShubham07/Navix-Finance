"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Gift, Phone, User } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import {
  ApplicationApiError,
  offerApi,
  REFERENCE_RELATIONS,
  type ReferenceInput,
  type ReferenceView,
} from "@/lib/api/applications";
import { useOffer, completeOfferStep, nextOfferRoute, prevOfferRoute } from "@/lib/offer";
import { formatApiError } from "@/lib/api/errors";
import { normalizeMobile } from "@/lib/utils";

const RELATION_LABEL: Record<string, string> = {
  PARENT: "Parent",
  SPOUSE: "Spouse",
  SIBLING: "Sibling",
  RELATIVE: "Relative",
  FRIEND: "Friend",
  COLLEAGUE: "Colleague",
  MANAGER: "Manager",
  NEIGHBOUR: "Neighbour",
};

const MOBILE_RE = /^[6-9]\d{9}$/;
const EMPTY: ReferenceInput = { fullName: "", mobile: "", relation: "" };

/**
 * Screen 4: two references — one family, one work in practice, though the relation list is open.
 *
 * <p>Despite the "Refer &amp; Earn" framing the spec asks for, these are capture-only and are
 * deliberately not wired to the referral rewards program (revamp.md decision 39) — that runs on
 * codes redeemed at signup, not on names typed here. The heading is the borrower-facing copy; the
 * behaviour is a reference check.
 */
export default function LoanReferencesPage() {
  const router = useRouter();
  const { appId } = useOffer();
  const [refs, setRefs] = React.useState<[ReferenceInput, ReferenceInput]>([{ ...EMPTY }, { ...EMPTY }]);
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();
  // References still to confirm their code (only with the reference-otp flag on); null shows the form.
  const [waiting, setWaiting] = React.useState<ReferenceView[] | null>(null);
  const [codes, setCodes] = React.useState<Record<number, string>>({});

  const sendCode = (slot: number) => {
    if (appId == null) return;
    offerApi.sendReferenceOtp(appId, slot).catch((err) => {
      // Coming back to this screen inside the resend wait: the earlier code is still good.
      if (err instanceof ApplicationApiError && err.code === "REFERENCE_OTP_COOLDOWN") return;
      setError(formatApiError(err, "Could not send the code — please try again."));
    });
  };

  const finish = (rows: ReferenceView[]) => {
    const left = rows.filter((r) => r.otpRequired && !r.verified);
    if (left.length > 0) return left;
    return completeOfferStep(appId as number, "OFFER_REFERENCES", router, nextOfferRoute("references")).then(() => null);
  };

  const confirmCode = async (slot: number) => {
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      const left = await finish(await offerApi.verifyReferenceOtp(appId, slot, (codes[slot] ?? "").trim()));
      if (left) {
        setWaiting(left);
        setBusy(false);
      }
    } catch (err) {
      setError(formatApiError(err, "Could not confirm the code — please try again."));
      setBusy(false);
    }
  };

  // Hydrate anything already saved, so coming back doesn't mean retyping (revamp.md decision 26).
  React.useEffect(() => {
    if (appId == null) return;
    let live = true;
    offerApi
      .references(appId)
      .then((saved) => {
        if (!live || saved.length === 0) return;
        const next: [ReferenceInput, ReferenceInput] = [{ ...EMPTY }, { ...EMPTY }];
        saved.forEach((r) => {
          if (r.slot === 1 || r.slot === 2) {
            next[r.slot - 1] = { fullName: r.fullName, mobile: r.mobile, relation: r.relation };
          }
        });
        setRefs(next);
        // Back on this screen with codes still owed: show code entry, without spending another send.
        const left = saved.filter((r) => r.otpRequired && !r.verified);
        if (left.length > 0) setWaiting(left);
      })
      .catch(() => {});
    return () => { live = false; };
  }, [appId]);

  const set = (i: 0 | 1, patch: Partial<ReferenceInput>) =>
    setRefs((prev) => {
      const next: [ReferenceInput, ReferenceInput] = [{ ...prev[0] }, { ...prev[1] }];
      next[i] = { ...next[i], ...patch };
      return next;
    });

  const valid = (r: ReferenceInput) =>
    r.fullName.trim().length > 1 && MOBILE_RE.test(r.mobile) && r.relation !== "";
  const sameNumber = refs[0].mobile !== "" && refs[0].mobile === refs[1].mobile;
  const formOk = valid(refs[0]) && valid(refs[1]) && !sameNumber;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formOk) { setTouched(true); return; }
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      const left = await finish(await offerApi.saveReferences(appId, [refs[0], refs[1]]));
      if (left) {
        setWaiting(left);
        setBusy(false);
        left.forEach((r) => sendCode(r.slot));
      }
    } catch (err) {
      setError(formatApiError(err, "Could not save your references — please try again."));
      setBusy(false);
    }
  };

  if (waiting) {
    return (
      <div className="form-card">
        <p className="font-semibold text-navy">Ask your references for their codes</p>
        <p className="mb-4 text-sm text-muted">
          We sent each person a 6-digit code. They should share it with you only if they agree to be your reference.
        </p>
        {waiting.map((r) => (
          <div key={r.slot} className="mb-5">
            <Input
              label={`Code from ${r.fullName} (••••••${r.mobile.slice(-4)})`}
              inputMode="numeric"
              maxLength={6}
              value={codes[r.slot] ?? ""}
              onChange={(e) => setCodes((c) => ({ ...c, [r.slot]: e.target.value.replace(/\D/g, "") }))}
            />
            <div className="mt-2 flex gap-2">
              <button
                type="button"
                className="btn btn-navy btn-sm"
                disabled={busy || (codes[r.slot] ?? "").length !== 6}
                onClick={() => confirmCode(r.slot)}
              >
                Confirm
              </button>
              <button type="button" className="btn btn-outline btn-sm" disabled={busy} onClick={() => sendCode(r.slot)}>
                Send again
              </button>
            </div>
          </div>
        ))}
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
        <button type="button" className="btn btn-outline btn-sm mt-2" onClick={() => { setError(undefined); setWaiting(null); }}>
          Change my references
        </button>
      </div>
    );
  }

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <div className="mb-4 flex items-start gap-3 rounded border border-line bg-navy-tint p-4">
          <Gift size={18} className="mt-0.5 flex-shrink-0 text-navy" />
          <div>
            <p className="font-semibold text-navy">Refer &amp; Earn Gifts and Vouchers</p>
            <p className="text-sm text-muted">
              Add two people who know you. We may contact them only if we can&apos;t reach you.
            </p>
          </div>
        </div>

        {([0, 1] as const).map((i) => (
          <fieldset key={i} className="mb-6 last:mb-0">
            <legend className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted">
              Reference {i + 1}
            </legend>
            <Input
              label="Full name"
              required
              value={refs[i].fullName}
              onChange={(e) => set(i, { fullName: e.target.value })}
              placeholder="Ravi Kumar"
              leftIcon={<User size={16} />}
              error={
                touched && refs[i].fullName.trim().length <= 1 ? "Enter their full name" : undefined
              }
            />
            <Input
              label="Mobile number"
              required
              inputMode="numeric"
              value={refs[i].mobile}
              onChange={(e) => set(i, { mobile: normalizeMobile(e.target.value) })}
              placeholder="9876543210"
              leftIcon={<Phone size={16} />}
              error={
                touched && !MOBILE_RE.test(refs[i].mobile)
                  ? "Enter a valid 10-digit mobile number"
                  : touched && i === 1 && sameNumber
                    ? "Your two references must have different mobile numbers"
                    : undefined
              }
            />
            <Select
              label="Relation"
              required
              value={refs[i].relation}
              onChange={(e) => set(i, { relation: e.target.value })}
              error={touched && refs[i].relation === "" ? "Choose a relation" : undefined}
            >
              <option value="">Select…</option>
              {REFERENCE_RELATIONS.map((r) => (
                <option key={r} value={r}>
                  {RELATION_LABEL[r] ?? r}
                </option>
              ))}
            </Select>
          </fieldset>
        ))}

        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      <WizardActions backHref={prevOfferRoute("references")} submit loading={busy} disabled={busy} />
      <Reassurance />
    </form>
  );
}
