"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { CheckCircle2, Landmark, Loader2, Lock, UploadCloud, User } from "lucide-react";
import { Input } from "@/components/ui";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { offerApi, verificationApi } from "@/lib/api/applications";
import { useOffer } from "@/lib/offer";
import { formatDateTime } from "@/lib/utils";
import { formatApiError } from "@/lib/api/errors";

const IFSC_RE = /^[A-Z]{4}0[A-Z0-9]{6}$/;

// Cancelled cheques and passbook photos arrive the same way bank statements do (signup/bank):
// a photo or a scanned PDF, never anything larger than a phone camera would produce.
const BANK_PROOF_ACCEPT = "application/pdf,image/jpeg,image/png";
const BANK_PROOF_MAX_BYTES = 10 * 1024 * 1024;

function maskAccount(account: string | null): string {
  if (!account || account.length < 4) return "—";
  return `•••• ${account.slice(-4)}`;
}

/**
 * Screen 11, the last one: where this advance is paid.
 *
 * <p>Confirming the salary account as-is costs nothing and cannot fail. Entering a different account
 * runs a penny drop, and three failures earn a 12-hour lock (revamp.md decisions 9 and 40) — so the
 * "use a different account" path is deliberately the secondary one, behind a link.
 *
 * <p>On success the application leaves the borrower's hands: the backend transitions it to
 * DISBURSEMENT_PENDING, so this page routes to the live status tracker rather than another step.
 */
export default function DisbursalAccountPage() {
  const router = useRouter();
  const { appId } = useOffer();
  const [changing, setChanging] = React.useState(false);
  const [account, setAccount] = React.useState("");
  const [ifsc, setIfsc] = React.useState("");
  const [holder, setHolder] = React.useState("");
  const [touched, setTouched] = React.useState(false);
  const [busy, setBusy] = React.useState(false);
  const [error, setError] = React.useState<string>();

  // The bank-proof fallback is a wholly separate submit path — it never touches the penny-drop
  // attempt counter or the lock, so its own file/busy/error state stays independent of the block above.
  const [bankProofFile, setBankProofFile] = React.useState<File | null>(null);
  const [bankProofBusy, setBankProofBusy] = React.useState(false);
  const [bankProofError, setBankProofError] = React.useState<string>();

  const q = useQuery({
    queryKey: ["disbursal-account", appId],
    queryFn: () => offerApi.disbursalAccount(appId as number),
    enabled: appId != null,
  });
  const saved = q.data;

  const lockedUntil = saved?.lockedUntil ?? null;
  const isLocked = lockedUntil != null && new Date(lockedUntil).getTime() > Date.now();

  const accountOk = /^\d{9,18}$/.test(account);
  const ifscOk = IFSC_RE.test(ifsc);
  const holderOk = holder.trim().length > 1;
  const formOk = !changing || (accountOk && ifscOk && holderOk);

  const submit = async () => {
    if (appId == null || !saved) return;
    if (changing && !formOk) { setTouched(true); return; }
    setBusy(true);
    setError(undefined);
    try {
      await offerApi.confirmDisbursalAccount(appId, {
        accountNumber: changing ? account : (saved.accountNumber ?? ""),
        ifsc: changing ? ifsc : (saved.ifsc ?? ""),
        holderName: changing ? holder.trim() : (saved.holderName ?? undefined),
        bank: changing ? undefined : (saved.bank ?? undefined),
      });
      router.push("/loan/status");
    } catch (err) {
      setError(formatApiError(err, "Invalid account details, please enter the bank details again."));
      setBusy(false);
      // A failed drop burns an attempt — re-read so the remaining count and any lock are current.
      void q.refetch();
    }
  };

  const pickBankProof = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0] ?? null;
    e.target.value = ""; // allow re-picking the same file after a mistaken selection
    if (!f) return;
    if (!BANK_PROOF_ACCEPT.split(",").includes(f.type)) {
      setBankProofError("Upload a PDF, JPG or PNG file.");
      return;
    }
    if (f.size > BANK_PROOF_MAX_BYTES) {
      setBankProofError("File must be under 10 MB.");
      return;
    }
    setBankProofError(undefined);
    setBankProofFile(f);
  };

  /**
   * The fallback path: no penny drop, no lock, no attempts counter — that machinery is exactly
   * what this exists to route around. The account number/IFSC/holder name are still required
   * (the proof confirms the account is theirs; it doesn't tell us where to send money), so this
   * reuses the same `changing` fields — or the saved salary account when the borrower never
   * switched — and the same `formOk` validity gate as the penny-drop submit above.
   */
  const submitBankProof = async () => {
    if (appId == null || !saved) return;
    if (!bankProofFile) { setBankProofError("Choose a cancelled cheque or passbook photo first."); return; }
    if (changing && !formOk) { setTouched(true); return; }
    setBankProofBusy(true);
    setBankProofError(undefined);
    try {
      const contentType = bankProofFile.type || "application/octet-stream";
      const { key, url } = await verificationApi.presignUpload(appId, {
        docType: "BANK_PROOF",
        fileName: bankProofFile.name,
        contentType,
      });
      await verificationApi.putToPresignedUrl(url, bankProofFile, contentType);
      await verificationApi.uploadedDocuments(appId, { docType: "BANK_PROOF", objectKeys: [key] });
      await offerApi.confirmDisbursalAccount(appId, {
        accountNumber: changing ? account : (saved.accountNumber ?? ""),
        ifsc: changing ? ifsc : (saved.ifsc ?? ""),
        holderName: changing ? holder.trim() : (saved.holderName ?? undefined),
        bank: changing ? undefined : (saved.bank ?? undefined),
        useBankProof: true,
      });
      router.push("/loan/status");
    } catch (err) {
      setBankProofError(formatApiError(err, "Could not upload your proof — please try again."));
      setBankProofBusy(false);
    }
  };

  if (q.isLoading || !saved) {
    return (
      <p className="flex items-center gap-2 text-sm text-muted">
        <Loader2 size={16} className="animate-spin" /> Loading your bank details…
      </p>
    );
  }

  return (
    <div>
      <div className="form-card">
        <p className="lead mb-1">Where should we send the money?</p>
        <p className="mb-5 text-sm text-muted">
          This is the salary account you gave us. Confirm it, or use a different one.
        </p>

        {!changing ? (
          <>
            <dl className="rounded border border-line bg-white shadow-sm">
              <Row label="Bank" value={saved.bank ?? "—"} />
              <Row label="Account number" value={maskAccount(saved.accountNumber)} />
              <Row label="IFSC" value={saved.ifsc ?? "—"} />
              <Row label="Account holder" value={saved.holderName ?? "—"} />
            </dl>
            {saved.verified ? (
              <p className="mt-3 flex items-center gap-1.5 text-sm text-success-700">
                <CheckCircle2 size={15} /> We&apos;ll reuse the successful verification for this account.
              </p>
            ) : (
              <p className="mt-3 text-sm text-muted">
                Confirming will perform a ₹1 account check. {saved.attemptsLeft} attempt{saved.attemptsLeft === 1 ? "" : "s"} remaining.
              </p>
            )}
            <button
              type="button"
              onClick={() => setChanging(true)}
              className="mt-4 text-sm font-semibold text-navy hover:underline"
            >
              Send it to a different account instead
            </button>
          </>
        ) : (
          <>
            <Input
              label="Account number"
              required
              inputMode="numeric"
              value={account}
              onChange={(e) => setAccount(e.target.value.replace(/\D/g, "").slice(0, 18))}
              placeholder="00123456789012"
              leftIcon={<Landmark size={16} />}
              error={touched && !accountOk ? "Enter a valid account number" : undefined}
            />
            <Input
              label="IFSC code"
              required
              value={ifsc}
              onChange={(e) => setIfsc(e.target.value.toUpperCase().replace(/\s/g, "").slice(0, 11))}
              placeholder="HDFC0001234"
              autoCapitalize="characters"
              error={touched && !ifscOk ? "Enter a valid 11-character IFSC" : undefined}
            />
            <Input
              label="Account holder name"
              required
              value={holder}
              onChange={(e) => setHolder(e.target.value)}
              placeholder="As it appears at your bank"
              leftIcon={<User size={16} />}
              error={touched && !holderOk ? "Enter the name on the account" : undefined}
            />
            <p className="text-xs text-muted">
              We&apos;ll send ₹1 to check this account is real and belongs to you.
              {!isLocked && saved.attemptsLeft > 0
                ? ` ${saved.attemptsLeft} attempt${saved.attemptsLeft === 1 ? "" : "s"} remaining.`
                : ""}
            </p>
            <button
              type="button"
              onClick={() => { setChanging(false); setError(undefined); setTouched(false); }}
              className="mt-3 text-sm font-semibold text-navy hover:underline"
            >
              Use my salary account instead
            </button>
          </>
        )}

        {isLocked ? (
          <p className="mt-4 flex items-start gap-2 rounded border border-warning-100 bg-warning-50 p-4 text-sm text-warning-800">
            <Lock size={15} className="mt-0.5 flex-shrink-0" />
            Too many failed account checks. Try again after {formatDateTime(lockedUntil)}.
          </p>
        ) : null}

        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      {/* The bank-proof fallback. Deliberately ALWAYS rendered — including while `isLocked` — because
          the 12-hour penny-drop lock is exactly the situation this exists to unblock. Nothing here
          reads `isLocked` or `saved.attemptsLeft`; wiring either in would recreate the dead end. */}
      <div className="form-card mt-4">
        <p className="lead mb-1">Not able to verify your account?</p>
        <p className="mb-4 text-sm text-muted">
          Upload a cancelled cheque or your bank passbook instead — we&apos;ll verify it manually
          before your advance is sent.
        </p>
        <input
          type="file"
          accept={BANK_PROOF_ACCEPT}
          onChange={pickBankProof}
          className="block w-full text-sm text-ink file:mr-3 file:rounded file:border-0 file:bg-navy file:px-3 file:py-1.5 file:text-sm file:font-semibold file:text-white file:cursor-pointer"
        />
        {bankProofFile ? (
          <p className="mt-2 text-sm text-muted">Selected: {bankProofFile.name}</p>
        ) : null}
        {bankProofError ? <p className="mt-2 text-sm text-error-600">{bankProofError}</p> : null}
        <button
          type="button"
          onClick={submitBankProof}
          disabled={bankProofBusy || !bankProofFile || (changing && !formOk)}
          className="btn btn-outline btn-sm mt-3"
        >
          <UploadCloud size={15} />
          {bankProofBusy ? "Uploading…" : "Upload proof & continue"}
        </button>
      </div>

      {/* `formOk` was computed but never reached the button: on the "different account" form,
          Verify & confirm stayed clickable with all three required fields empty. Clicking it burns
          one of only three penny-drop attempts before a 12-hour lock, so an accidental submit is
          expensive here in a way it is not on the other screens. */}
      <WizardActions
        continueLabel={changing ? "Verify & confirm" : "Confirm this account"}
        onContinue={submit}
        loading={busy}
        disabled={busy || (isLocked && (changing || saved.pennyDropRequired)) || !formOk}
      />
      <Reassurance />
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between border-b border-grey-200 px-5 py-3 last:border-0">
      <dt className="text-sm text-muted">{label}</dt>
      <dd className="font-mono text-sm font-semibold text-ink">{value}</dd>
    </div>
  );
}
