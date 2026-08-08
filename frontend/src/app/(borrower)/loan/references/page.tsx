"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { Gift, Phone, User } from "lucide-react";
import { Input, Select } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { offerApi, REFERENCE_RELATIONS, type ReferenceInput } from "@/lib/api/applications";
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
      await offerApi.saveReferences(appId, [refs[0], refs[1]]);
      await completeOfferStep(appId, "OFFER_REFERENCES", router, nextOfferRoute("references"));
    } catch (err) {
      setError(formatApiError(err, "Could not save your references — please try again."));
      setBusy(false);
    }
  };

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
