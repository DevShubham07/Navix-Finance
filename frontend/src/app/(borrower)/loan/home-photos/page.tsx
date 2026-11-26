"use client";

import * as React from "react";
import { useRouter } from "next/navigation";
import { WizardActions } from "@/components/borrower/wizard-actions";
import { Reassurance } from "@/components/borrower/reassurance";
import { SideTile, type SideState } from "@/components/borrower/card-sides-upload";
import { useOffer, completeOfferStep, nextOfferRoute, prevOfferRoute } from "@/lib/offer";
import { borrowerApi, verificationApi, type DocumentView } from "@/lib/api/applications";
import { compressImage } from "@/lib/compress-image";
import { checkDocumentFile } from "@/lib/upload-file-types";
import { formatApiError } from "@/lib/api/errors";

/**
 * Four compulsory home photos, right after the geolocation address. Camera-only on phones (`capture`);
 * desktop browsers fall back to a file picker. The backend refuses to sign without all four
 * (`HOME_PHOTOS_REQUIRED`) and `JourneyService` holds the borrower here until they are on file.
 */
const PHOTOS = [
  { docType: "HOME_INSIDE", label: "Inside your home", camera: "environment" },
  { docType: "HOME_OUTSIDE", label: "Outside your home", camera: "environment" },
  { docType: "HOME_SELFIE", label: "Selfie inside your home", camera: "user" },
  { docType: "HOME_STREET", label: "Your street / lane", camera: "environment" },
] as const;

type Slots = Record<string, SideState>;
const EMPTY: Slots = Object.fromEntries(PHOTOS.map((p) => [p.docType, { file: null, existing: null }]));

export default function LoanHomePhotosPage() {
  const router = useRouter();
  const { appId } = useOffer();
  const [slots, setSlots] = React.useState<Slots>(EMPTY);
  const [loading, setLoading] = React.useState(true);
  const [busy, setBusy] = React.useState(false);
  const [touched, setTouched] = React.useState(false);
  const [error, setError] = React.useState<string>();

  // Resume: photos already on file count as done and are not re-uploaded.
  React.useEffect(() => {
    if (appId == null) return;
    let live = true;
    borrowerApi
      .documents(appId)
      .then((docs) => {
        if (!live) return;
        setSlots((s) => {
          const next = { ...s };
          for (const p of PHOTOS) {
            const existing = [...docs].reverse().find((d) => d.docType.toUpperCase() === p.docType) ?? null;
            next[p.docType] = { ...next[p.docType], existing };
          }
          return next;
        });
      })
      .catch(() => {})
      .finally(() => live && setLoading(false));
    return () => {
      live = false;
    };
  }, [appId]);

  const pick = (docType: string, file: File | null) => {
    const problem = file ? checkDocumentFile(file) : undefined;
    if (problem) return setError(problem);
    setError(undefined);
    setSlots((s) => ({ ...s, [docType]: { ...s[docType], file } }));
  };

  const ready = PHOTOS.every((p) => slots[p.docType].file || slots[p.docType].existing);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!ready) return setTouched(true);
    if (appId == null) return;
    setBusy(true);
    setError(undefined);
    try {
      for (const p of PHOTOS) {
        const file = slots[p.docType].file;
        if (!file) continue;
        const prepared = await compressImage(file);
        const contentType = prepared.type || "image/jpeg";
        const { key, url } = await verificationApi.presignUpload(appId, {
          docType: p.docType,
          fileName: prepared.name,
          contentType,
        });
        await verificationApi.putToPresignedUrl(url, prepared, contentType);
        await verificationApi.uploadedDocuments(appId, { docType: p.docType, objectKeys: [key] });
        // Mark it persisted so a failure on a later photo (and the retry) does not redo this one.
        const done: DocumentView = {
          id: -1,
          docType: p.docType,
          fileName: prepared.name,
          contentType,
          sizeBytes: prepared.size,
          uploadedAt: new Date().toISOString(),
          s3: true,
        };
        setSlots((s) => ({ ...s, [p.docType]: { file: null, existing: done } }));
      }
      await completeOfferStep(appId, "OFFER_HOME_PHOTOS", router, nextOfferRoute("home-photos"));
    } catch (err) {
      setError(formatApiError(err, "Could not upload your photos. Please try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate>
      <div className="form-card">
        <p className="lead mb-4">
          Take four photos of where you live. All four are required — use your phone&apos;s camera.
        </p>
        <div className="grid gap-3 sm:grid-cols-2">
          {PHOTOS.map((p) => (
            <SideTile
              key={p.docType}
              label={p.label}
              state={slots[p.docType]}
              onPick={(f) => pick(p.docType, f)}
              disabled={busy}
              loading={loading}
              cameraOnly={p.camera}
            />
          ))}
        </div>
        {touched && !ready ? (
          <p className="mt-3 text-sm text-error-600">Take all four photos to continue.</p>
        ) : null}
        {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
      </div>
      <WizardActions backHref={prevOfferRoute("home-photos")} submit loading={busy} disabled={busy || loading} />
      <Reassurance />
    </form>
  );
}
