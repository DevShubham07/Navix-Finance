"use client";

import * as React from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { CheckCircle2, FileCheck2, Phone, UploadCloud } from "lucide-react";
import { useOnboarding } from "@/lib/onboarding";
import { borrowerApi, journeyApi, verificationApi } from "@/lib/api/applications";
import { formatApiError } from "@/lib/api/errors";
import { BRAND } from "@/lib/brand";

const ACCEPT = "application/pdf,image/jpeg,image/png";
const MAX_BYTES = 10 * 1024 * 1024;
const EXTRA_DOCS = [
  { docType: "ELECTRICITY_BILL", label: "Electricity bill" },
  { docType: "GAS_BILL", label: "Gas bill" },
  { docType: "RENT_AGREEMENT", label: "Rent agreement" },
] as const;

/**
 * Screen 10 — the application goes to the credit team here. The extra documents below are an
 * optional accelerator (revamp.md decision 18): skipping them changes nothing about the outcome.
 */
export default function SignupSubmittedPage() {
  const router = useRouter();
  const { mounted, appId } = useOnboarding();
  const [submitted, setSubmitted] = React.useState(false);
  const [uploaded, setUploaded] = React.useState<Record<string, string>>({});
  const [error, setError] = React.useState<string>();
  const done = React.useRef(false);

  React.useEffect(() => {
    if (!mounted) return;
    if (appId == null) {
      router.replace("/signup/start");
      return;
    }
    if (done.current) return;
    done.current = true;
    borrowerApi
      .submitKyc(appId)
      .then(() => {
        setSubmitted(true);
        // Best-effort pointer update; the status leaving DRAFT is what really ends the intake.
        return journeyApi.advance(appId, "SUBMITTED").catch(() => {});
      })
      .catch((e) => setError(formatApiError(e, "We couldn't submit your application.")));
  }, [mounted, appId]); // eslint-disable-line react-hooks/exhaustive-deps

  const upload = (docType: string) => async (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (!f || appId == null) return;
    if (f.size > MAX_BYTES) {
      setError("File must be under 10 MB.");
      return;
    }
    setError(undefined);
    try {
      const contentType = f.type || "application/octet-stream";
      const { url } = await verificationApi.presignUpload(appId, { docType, fileName: f.name, contentType });
      await verificationApi.putToPresignedUrl(url, f, contentType);
      setUploaded((prev) => ({ ...prev, [docType]: f.name }));
    } catch (err) {
      setError(formatApiError(err, "Upload failed — please try again."));
    }
  };

  return (
    <div>
      <div className="form-card text-center">
        <CheckCircle2 size={40} className="mx-auto text-success-600" />
        <h3 className="mt-3 font-serif text-xl text-navy">Your application is with our credit team</h3>
        <p className="mt-2 text-sm text-muted">
          {submitted
            ? "We'll be in touch as soon as it's reviewed. You can close this page — we'll notify you."
            : "Finishing up…"}
        </p>
      </div>

      <div className="form-card mt-4">
        <p className="font-serif text-lg text-navy">Help us process your loan faster</p>
        <p className="mb-4 mt-1 text-sm text-muted">
          Optional — adding an address proof can speed up the review. Sit back comfortably; we&apos;ll do the rest.
        </p>
        <div className="space-y-3">
          {EXTRA_DOCS.map(({ docType, label }) => (
            <label
              key={docType}
              className={`flex w-full cursor-pointer items-center gap-3 rounded border-2 border-dashed p-4 transition ${
                uploaded[docType] ? "border-success-600 bg-success-50/50" : "border-line bg-grey-100 hover:border-navy"
              }`}
            >
              <input type="file" accept={ACCEPT} className="sr-only" onChange={upload(docType)} />
              {uploaded[docType] ? (
                <FileCheck2 size={20} className="flex-shrink-0 text-success-600" />
              ) : (
                <UploadCloud size={20} className="flex-shrink-0 text-navy" />
              )}
              <span className="min-w-0">
                <span className="block text-sm font-semibold text-navy">{label}</span>
                <span className="block truncate text-xs text-muted">
                  {uploaded[docType] ?? "PDF, JPG or PNG up to 10 MB"}
                </span>
              </span>
            </label>
          ))}
        </div>
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>

      <div className="mt-4 flex flex-col items-center gap-3 text-center">
        <p className="flex items-center gap-1.5 text-sm text-muted">
          <Phone size={15} /> Questions? Call us at{" "}
          <a href={`tel:${BRAND.phone.replace(/\s/g, "")}`} className="font-semibold text-navy hover:underline">
            {BRAND.phone}
          </a>
        </p>
        <Link href="/loan/status" className="btn btn-gold">
          Track my application
        </Link>
      </div>
    </div>
  );
}
