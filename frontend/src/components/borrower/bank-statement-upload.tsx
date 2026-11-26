"use client";

import * as React from "react";
import { FileCheck2, UploadCloud, X } from "lucide-react";
import { Input } from "@/components/ui";
import { verificationApi } from "@/lib/api/applications";
import { compressImage } from "@/lib/compress-image";
import { DOC_UPLOAD_ACCEPT, checkDocumentFile } from "@/lib/upload-file-types";

/** Presign + PUT each file, then record them as BANK_STATEMENT documents (optional PDF password). */
export async function uploadBankStatements(appId: number, files: File[], password: string) {
  const keys: string[] = [];
  for (const f of files) {
    const upload = await compressImage(f);
    const contentType = upload.type || "application/octet-stream";
    const { key, url } = await verificationApi.presignUpload(appId, {
      docType: "BANK_STATEMENT",
      fileName: upload.name,
      contentType,
    });
    await verificationApi.putToPresignedUrl(url, upload, contentType);
    keys.push(key);
  }
  await verificationApi.uploadedDocuments(appId, {
    docType: "BANK_STATEMENT",
    objectKeys: keys,
    filePassword: password.trim() || undefined,
  });
}

/** Controlled picker for one or many bank statements (moved verbatim from the signup bank page). */
export function BankStatementUpload({
  files,
  onFiles,
  password,
  onPassword,
  onError,
  showRequired,
}: {
  files: File[];
  onFiles: (updater: (prev: File[]) => File[]) => void;
  password: string;
  onPassword: (v: string) => void;
  onError: (e: string | undefined) => void;
  showRequired?: boolean;
}) {
  const has = files.length > 0;
  /** Appends every valid file from the picker/drop to the existing selection — one upload or many. */
  const pick = (e: React.ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(e.target.files ?? []);
    e.target.value = ""; // allow re-picking the same file(s) after a removal
    if (picked.length === 0) return;
    const accepted: File[] = [];
    for (const f of picked) {
      const problem = checkDocumentFile(f);
      if (problem) {
        onError(problem);
        continue;
      }
      accepted.push(f);
    }
    if (accepted.length > 0) {
      onError(undefined);
      onFiles((prev) => [...prev, ...accepted]);
    }
  };

  return (
    <>
      <div className="mt-4">
        <label
          className={`flex w-full cursor-pointer flex-col items-center gap-2 rounded border-2 border-dashed p-5 text-center transition ${
            has ? "border-success-600 bg-success-50/50" : "border-line bg-grey-100 hover:border-navy"
          }`}
        >
          <input type="file" accept={DOC_UPLOAD_ACCEPT} multiple className="sr-only" onChange={pick} />
          {has ? (
            <>
              <FileCheck2 size={24} className="text-success-600" />
              <span className="text-sm font-semibold text-success-700">
                {files.length} bank statement{files.length === 1 ? "" : "s"} added
              </span>
              <span className="text-xs text-muted">Tap to add more</span>
            </>
          ) : (
            <>
              <UploadCloud size={24} className="text-navy" />
              <span className="text-sm font-semibold text-navy">Bank statements (last 6 months)</span>
              <span className="text-xs text-muted">PDF or photo (JPG, PNG, HEIC) up to 10 MB each — upload one or several</span>
            </>
          )}
        </label>
        {has ? (
          <ul className="mt-2 space-y-1">
            {files.map((f, i) => (
              <li
                key={`${f.name}-${f.lastModified}-${i}`}
                className="flex items-center justify-between gap-2 rounded border border-line bg-white px-3 py-1.5 text-xs"
              >
                <span className="truncate text-ink">{f.name}</span>
                <button
                  type="button"
                  onClick={() => onFiles((prev) => prev.filter((_, idx) => idx !== i))}
                  className="flex-shrink-0 rounded p-0.5 text-muted hover:bg-grey-100 hover:text-error-700"
                  aria-label={`Remove ${f.name}`}
                >
                  <X size={14} />
                </button>
              </li>
            ))}
          </ul>
        ) : null}
        {showRequired && !has ? (
          <p className="mt-2 text-sm text-error-600">Upload at least one bank statement to continue.</p>
        ) : null}
      </div>

      {has ? (
        <Input
          label="PDF password (optional)"
          value={password}
          onChange={(e) => onPassword(e.target.value)}
          autoComplete="off"
          helperText="If your statement is password-protected, enter the password so our team can open it."
        />
      ) : null}
    </>
  );
}
