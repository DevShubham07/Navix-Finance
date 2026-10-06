"use client";

import * as React from "react";
import { Camera, FileCheck2, FileText, Loader2, UploadCloud, X } from "lucide-react";
import { Input } from "@/components/ui";
import { borrowerApi, verificationApi, type DocumentView } from "@/lib/api/applications";
import { compressImage } from "@/lib/compress-image";
import { DOC_UPLOAD_ACCEPT, checkDocumentFile } from "@/lib/upload-file-types";

/**
 * Front + back upload of an identity card (the V75 Aadhaar and PAN screens), shared so the two
 * screens cannot drift: one tile per side, a photo preview, a "take a photo" shortcut on phones, a
 * remove/replace control, and an optional password for a protected e-Aadhaar / e-PAN PDF.
 *
 * What "not broken" means here, because each of these bit a real borrower on an earlier upload:
 *  - resume: a side already on file (same device or another) shows as done and is NOT re-uploaded,
 *    so Continue works after a reload without re-picking anything;
 *  - a PDF is allowed (e-Aadhaar, e-PAN), so the main picker carries no `capture` attribute — on
 *    Android/iOS `capture` forces the camera and hides the file picker; the camera is a second,
 *    image-only input instead;
 *  - HEIC/odd MIME types pass `checkDocumentFile` and are re-encoded by `compressImage`;
 *  - the same file can be picked again after a remove (`input.value` is reset);
 *  - every object URL is revoked, and a half-finished upload leaves the other side's state alone.
 */

export type CardSide = "front" | "back";

export interface CardSideSpec {
  /** Persisted document type for this side (e.g. AADHAAR_CARD_FRONT). */
  docType: string;
  label: string;
}

interface SideState {
  file: File | null;
  /** A document of this type already on the application (resume on another device / a reload). */
  existing: DocumentView | null;
}

export interface CardSidesState {
  front: SideState;
  back: SideState;
  password: string;
  setPassword: (v: string) => void;
  pick: (side: CardSide, file: File | null) => void;
  /** True once both sides have either a picked file or an existing document. */
  ready: boolean;
  /** True while the existing-document lookup is in flight (Continue stays disabled). */
  loadingExisting: boolean;
  /** Any picked file is a PDF (drives whether the password field is offered). */
  hasPdf: boolean;
  error: string | undefined;
  setError: (e: string | undefined) => void;
  /** Presign → PUT → persist each side that holds a NEW file. Throws on failure; safe to retry. */
  upload: () => Promise<void>;
}

export function useCardSides(appId: number | null, spec: Record<CardSide, CardSideSpec>): CardSidesState {
  const [front, setFront] = React.useState<SideState>({ file: null, existing: null });
  const [back, setBack] = React.useState<SideState>({ file: null, existing: null });
  const [password, setPassword] = React.useState("");
  const [error, setError] = React.useState<string>();
  const [loadingExisting, setLoadingExisting] = React.useState(true);

  // Resume: what is already on file for this application decides which tiles start as "done".
  React.useEffect(() => {
    if (appId == null) return;
    let live = true;
    setLoadingExisting(true);
    borrowerApi
      .documents(appId)
      .then((docs) => {
        if (!live) return;
        const latest = (type: string) =>
          [...docs].reverse().find((d) => d.docType.toUpperCase() === type) ?? null;
        setFront((s) => ({ ...s, existing: latest(spec.front.docType) }));
        setBack((s) => ({ ...s, existing: latest(spec.back.docType) }));
      })
      .catch(() => {
        /* a failed lookup only loses the resume shortcut — the borrower can still upload */
      })
      .finally(() => live && setLoadingExisting(false));
    return () => {
      live = false;
    };
  }, [appId, spec.front.docType, spec.back.docType]);

  const pick = React.useCallback((side: CardSide, file: File | null) => {
    if (file) {
      const problem = checkDocumentFile(file);
      if (problem) {
        setError(problem);
        return;
      }
    }
    setError(undefined);
    (side === "front" ? setFront : setBack)((s) => ({ ...s, file }));
  }, []);

  const upload = React.useCallback(async () => {
    if (appId == null) throw new Error("No application");
    const filePassword = password.trim() || undefined;
    for (const [side, state] of [
      ["front", front],
      ["back", back],
    ] as const) {
      if (!state.file) continue; // already on file — never re-upload a side the borrower did not touch
      const docType = spec[side].docType;
      const prepared = await compressImage(state.file);
      const contentType = prepared.type || "application/octet-stream";
      const { key, url } = await verificationApi.presignUpload(appId, {
        docType,
        fileName: prepared.name,
        contentType,
      });
      await verificationApi.putToPresignedUrl(url, prepared, contentType);
      await verificationApi.uploadedDocuments(appId, { docType, objectKeys: [key], filePassword });
      // Mark this side persisted so a failure on the OTHER side (and the retry) does not redo it.
      const done: DocumentView = {
        id: -1,
        docType,
        fileName: prepared.name,
        contentType,
        sizeBytes: prepared.size,
        uploadedAt: new Date().toISOString(),
        s3: true,
      };
      (side === "front" ? setFront : setBack)({ file: null, existing: done });
    }
  }, [appId, back, front, password, spec]);

  const hasPdf = [front.file, back.file].some((f) => f != null && isPdf(f));
  const ready = Boolean((front.file || front.existing) && (back.file || back.existing));

  return { front, back, password, setPassword, pick, ready, loadingExisting, hasPdf, error, setError, upload };
}

function isPdf(f: File): boolean {
  return f.type === "application/pdf" || /\.pdf$/i.test(f.name);
}

export function CardSidesUpload({
  state,
  spec,
  cardName,
  disabled,
  touched,
}: {
  state: CardSidesState;
  spec: Record<CardSide, CardSideSpec>;
  /** "Aadhaar card" / "PAN card" — used in copy. */
  cardName: string;
  disabled?: boolean;
  /** Show the "both sides required" message (after a Continue attempt). */
  touched?: boolean;
}) {
  const showPassword = state.hasPdf;
  return (
    <div className="mt-4">
      <div className="grid gap-3 sm:grid-cols-2">
        {(["front", "back"] as const).map((side) => (
          <SideTile
            key={side}
            label={spec[side].label}
            state={state[side]}
            onPick={(f) => state.pick(side, f)}
            disabled={disabled}
            loading={state.loadingExisting}
          />
        ))}
      </div>
      <p className="mt-2 text-xs text-muted">
        Photo (JPG, PNG, HEIC) or PDF, up to 10 MB each. Make sure every corner of the {cardName} is
        visible and the text is readable.
      </p>
      {touched && !state.ready ? (
        <p className="mt-2 text-sm text-error-600">Upload both the front and the back of your {cardName} to continue.</p>
      ) : null}
      {showPassword ? (
        <Input
          className="mt-4"
          label="PDF password (optional)"
          value={state.password}
          onChange={(e) => state.setPassword(e.target.value)}
          autoComplete="off"
          disabled={disabled}
          helperText={`If your ${cardName} PDF is password-protected, enter the password so our team can open it.`}
        />
      ) : null}
      {state.error ? <p className="mt-3 text-sm text-error-600">{state.error}</p> : null}
    </div>
  );
}

function SideTile({
  label,
  state,
  onPick,
  disabled,
  loading,
}: {
  label: string;
  state: SideState;
  onPick: (file: File | null) => void;
  disabled?: boolean;
  loading?: boolean;
}) {
  const fileRef = React.useRef<HTMLInputElement>(null);
  const cameraRef = React.useRef<HTMLInputElement>(null);
  const [preview, setPreview] = React.useState<string | null>(null);

  // One object URL per picked image, revoked when the file changes or the tile unmounts.
  React.useEffect(() => {
    const f = state.file;
    if (!f || isPdf(f)) {
      setPreview(null);
      return;
    }
    const url = URL.createObjectURL(f);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [state.file]);

  const onChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0] ?? null;
    e.target.value = ""; // allow picking the same file again after a remove
    if (f) onPick(f);
  };

  const picked = state.file;
  const existing = !picked && state.existing;
  const done = Boolean(picked || existing);

  return (
    <div
      className={`relative flex min-h-[9.5rem] flex-col rounded border-2 border-dashed p-3 transition ${
        done ? "border-success-600 bg-success-50/50" : "border-line bg-grey-100"
      }`}
    >
      <input ref={fileRef} type="file" accept={DOC_UPLOAD_ACCEPT} className="sr-only" onChange={onChange} disabled={disabled} />
      {/* Camera shortcut: image-only + capture, so phones open the rear camera directly. Kept apart
          from the file input above because `capture` would hide the file picker (no PDFs). */}
      <input ref={cameraRef} type="file" accept="image/*" capture="environment" className="sr-only" onChange={onChange} disabled={disabled} />

      <div className="mb-2 flex items-center justify-between gap-2">
        <span className="text-sm font-semibold text-navy">{label}</span>
        {done ? (
          <button
            type="button"
            onClick={() => onPick(null)}
            disabled={disabled || Boolean(existing)}
            className="rounded p-0.5 text-muted hover:bg-white hover:text-error-700 disabled:invisible"
            aria-label={`Remove ${label}`}
          >
            <X size={14} />
          </button>
        ) : null}
      </div>

      {loading && !done ? (
        <div className="flex flex-1 items-center justify-center text-muted">
          <Loader2 size={18} className="animate-spin" />
        </div>
      ) : picked ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-1 text-center">
          {preview ? (
            // eslint-disable-next-line @next/next/no-img-element -- local object URL, not an asset
            <img src={preview} alt={`${label} preview`} className="max-h-24 rounded object-contain" />
          ) : (
            <FileText size={28} className="text-navy" />
          )}
          <span className="line-clamp-1 w-full break-all text-xs text-ink">{picked.name}</span>
          <button type="button" onClick={() => fileRef.current?.click()} disabled={disabled} className="text-xs font-semibold text-navy hover:underline">
            Replace
          </button>
        </div>
      ) : existing ? (
        <div className="flex flex-1 flex-col items-center justify-center gap-1 text-center">
          <FileCheck2 size={26} className="text-success-600" />
          <span className="text-xs font-semibold text-success-700">Already uploaded</span>
          <button type="button" onClick={() => fileRef.current?.click()} disabled={disabled} className="text-xs font-semibold text-navy hover:underline">
            Upload a different file
          </button>
        </div>
      ) : (
        <div className="flex flex-1 flex-col items-center justify-center gap-2">
          <UploadCloud size={24} className="text-navy" />
          <div className="flex flex-wrap items-center justify-center gap-2">
            <button type="button" onClick={() => fileRef.current?.click()} disabled={disabled} className="btn btn-sm btn-outline">
              Choose file
            </button>
            <button type="button" onClick={() => cameraRef.current?.click()} disabled={disabled} className="btn btn-sm btn-outline sm:hidden">
              <Camera size={14} /> Take photo
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
