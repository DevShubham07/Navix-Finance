"use client";

import * as React from "react";
import { createPortal } from "react-dom";
import { ExternalLink, FileText, Eye, Maximize2, RotateCw, Trash2, X, ZoomIn, ZoomOut } from "lucide-react";

/**
 * In-app viewer for a payment screenshot (or a PDF receipt), shared by every surface that shows
 * payment proof: the collections officer previewing a file before recording it, the Collection
 * Head and Accountant queues, the repayment verify queue, the ledger and Customer 360.
 *
 * Images get zoom in / out (buttons, +/- keys, Ctrl/⌘ + wheel), drag-to-pan when zoomed, rotate
 * and a reset. PDFs render in the browser's own viewer (which has its own zoom). `footer` lets a
 * queue put its decision buttons (validate / reject) under the image, so the checker decides while
 * looking at the proof.
 */

export type ProofKind = "image" | "pdf";

const MIN_ZOOM = 0.25;
const MAX_ZOOM = 6;
const STEP = 1.25;

/** Best guess of the kind from a URL (presigned S3 GETs carry the key's extension in the path). */
export function proofKindFromUrl(url: string): ProofKind {
  let path = url;
  try {
    path = new URL(url, "http://x").pathname;
  } catch {
    /* keep the raw string */
  }
  return /\.pdf$/i.test(path) ? "pdf" : "image";
}

export function proofKindFromFile(file: File): ProofKind {
  return file.type === "application/pdf" || /\.pdf$/i.test(file.name) ? "pdf" : "image";
}

export function ProofViewer({
  src,
  kind,
  title = "Payment screenshot",
  open,
  onClose,
  footer,
}: {
  src: string;
  kind?: ProofKind;
  title?: string;
  open: boolean;
  onClose: () => void;
  /** Rendered under the proof — e.g. the checker's Validate / Reject buttons. */
  footer?: React.ReactNode;
}) {
  const resolvedKind = kind ?? proofKindFromUrl(src);
  const [mounted, setMounted] = React.useState(false);
  const [zoom, setZoom] = React.useState(1);
  const [rotation, setRotation] = React.useState(0);
  const [offset, setOffset] = React.useState({ x: 0, y: 0 });
  const [failed, setFailed] = React.useState(false);
  const drag = React.useRef<{ x: number; y: number; ox: number; oy: number } | null>(null);
  const [dragging, setDragging] = React.useState(false);

  React.useEffect(() => setMounted(true), []);

  const reset = React.useCallback(() => {
    setZoom(1);
    setRotation(0);
    setOffset({ x: 0, y: 0 });
  }, []);

  const zoomBy = React.useCallback((factor: number) => {
    setZoom((z) => {
      const next = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, z * factor));
      if (next <= 1) setOffset({ x: 0, y: 0 });
      return next;
    });
  }, []);

  // Fresh view per open / per file.
  React.useEffect(() => {
    if (open) {
      reset();
      setFailed(false);
    }
  }, [open, src, reset]);

  React.useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const typing = target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable);
      if (e.key === "Escape") onClose();
      else if (typing || resolvedKind !== "image") return;
      else if (e.key === "+" || e.key === "=") zoomBy(STEP);
      else if (e.key === "-") zoomBy(1 / STEP);
      else if (e.key === "0") reset();
    };
    document.addEventListener("keydown", onKey);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [open, onClose, zoomBy, reset, resolvedKind]);

  if (!open || !mounted) return null;

  const onWheel = (e: React.WheelEvent) => {
    if (resolvedKind !== "image") return;
    // Plain wheel zooms too — the stage has nothing else to scroll.
    zoomBy(e.deltaY < 0 ? 1.1 : 1 / 1.1);
  };

  const onPointerDown = (e: React.PointerEvent) => {
    if (zoom <= 1) return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    drag.current = { x: e.clientX, y: e.clientY, ox: offset.x, oy: offset.y };
    setDragging(true);
  };
  const onPointerMove = (e: React.PointerEvent) => {
    if (!drag.current) return;
    setOffset({ x: drag.current.ox + e.clientX - drag.current.x, y: drag.current.oy + e.clientY - drag.current.y });
  };
  const endDrag = () => {
    drag.current = null;
    setDragging(false);
  };

  const toolBtn =
    "grid h-9 w-9 place-items-center rounded-full bg-white/95 text-navy shadow transition hover:bg-white active:scale-95 disabled:opacity-40";

  return createPortal(
    <div
      className="fixed inset-0 z-[220] flex flex-col bg-black/90"
      role="dialog"
      aria-modal="true"
      aria-label={title}
      onClick={onClose}
    >
      {/* Toolbar */}
      <div
        className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 sm:px-5"
        onClick={(e) => e.stopPropagation()}
      >
        <span className="truncate text-sm font-semibold text-white">{title}</span>
        <div className="flex items-center gap-1.5">
          {resolvedKind === "image" && !failed && (
            <>
              <button type="button" className={toolBtn} onClick={() => zoomBy(1 / STEP)} disabled={zoom <= MIN_ZOOM} aria-label="Zoom out" title="Zoom out (−)">
                <ZoomOut size={17} />
              </button>
              <span className="min-w-[3.5rem] rounded-full bg-white/15 px-2 py-1 text-center text-xs font-semibold tabular-nums text-white">
                {Math.round(zoom * 100)}%
              </span>
              <button type="button" className={toolBtn} onClick={() => zoomBy(STEP)} disabled={zoom >= MAX_ZOOM} aria-label="Zoom in" title="Zoom in (+)">
                <ZoomIn size={17} />
              </button>
              <button type="button" className={toolBtn} onClick={() => setRotation((r) => (r + 90) % 360)} aria-label="Rotate" title="Rotate">
                <RotateCw size={16} />
              </button>
              <button type="button" className={toolBtn} onClick={reset} aria-label="Reset view" title="Fit to screen (0)">
                <Maximize2 size={16} />
              </button>
            </>
          )}
          <a
            href={src}
            target="_blank"
            rel="noopener noreferrer"
            className={toolBtn}
            aria-label="Open in a new tab"
            title="Open in a new tab"
          >
            <ExternalLink size={16} />
          </a>
          <button type="button" className={toolBtn} onClick={onClose} aria-label="Close" title="Close (Esc)">
            <X size={19} />
          </button>
        </div>
      </div>

      {/* Stage */}
      <div
        className="relative flex flex-1 items-center justify-center overflow-hidden px-3 pb-3 sm:px-6"
        onWheel={onWheel}
      >
        {resolvedKind === "pdf" ? (
          <iframe
            src={src}
            title={title}
            className="h-full w-full max-w-5xl rounded-lg bg-white"
            onClick={(e) => e.stopPropagation()}
          />
        ) : failed ? (
          <div className="rounded-lg bg-white p-6 text-center text-sm text-ink" onClick={(e) => e.stopPropagation()}>
            This file could not be shown as an image.{" "}
            <a href={src} target="_blank" rel="noopener noreferrer" className="font-semibold text-navy underline">
              Open it in a new tab
            </a>
            .
          </div>
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={src}
            alt={title}
            draggable={false}
            onError={() => setFailed(true)}
            onClick={(e) => e.stopPropagation()}
            onDoubleClick={() => (zoom === 1 ? zoomBy(2) : reset())}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={endDrag}
            onPointerCancel={endDrag}
            style={{
              transform: `translate(${offset.x}px, ${offset.y}px) scale(${zoom}) rotate(${rotation}deg)`,
              transition: dragging ? "none" : "transform 120ms ease-out",
              cursor: zoom > 1 ? (dragging ? "grabbing" : "grab") : "zoom-in",
              touchAction: "none",
            }}
            className="max-h-full max-w-full select-none rounded-md bg-white object-contain shadow-2xl"
          />
        )}
      </div>

      {footer ? (
        <div className="border-t border-white/10 bg-white px-4 py-3" onClick={(e) => e.stopPropagation()}>
          <div className="mx-auto flex max-w-3xl flex-wrap items-end justify-end gap-3">{footer}</div>
        </div>
      ) : (
        resolvedKind === "image" && (
          <p className="pb-3 text-center text-xs text-white/60">
            Scroll or use +/− to zoom · drag to move · double-click to toggle zoom
          </p>
        )
      )}
    </div>,
    document.body,
  );
}

/** A small clickable thumbnail of a proof that opens the viewer. */
export function ProofThumbnail({
  src,
  kind,
  title,
  footer,
  className,
}: {
  src: string;
  kind?: ProofKind;
  title?: string;
  footer?: (close: () => void) => React.ReactNode;
  className?: string;
}) {
  const [open, setOpen] = React.useState(false);
  const [broken, setBroken] = React.useState(false);
  const resolvedKind = kind ?? proofKindFromUrl(src);
  const close = React.useCallback(() => setOpen(false), []);
  return (
    <>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          setOpen(true);
        }}
        className={`group relative inline-grid h-16 w-16 flex-shrink-0 cursor-zoom-in place-items-center overflow-hidden rounded border border-line bg-cream ${className ?? ""}`}
        aria-label="View payment screenshot"
        title="View payment screenshot"
      >
        {resolvedKind === "pdf" || broken ? (
          <FileText size={22} className="text-navy" />
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={src} alt="" className="h-full w-full object-cover" onError={() => setBroken(true)} />
        )}
        <span className="pointer-events-none absolute bottom-0.5 right-0.5 grid h-5 w-5 place-items-center rounded-full bg-navy/80 text-white">
          <ZoomIn size={11} />
        </span>
      </button>
      <ProofViewer src={src} kind={resolvedKind} title={title} open={open} onClose={close} footer={footer?.(close)} />
    </>
  );
}

/**
 * File picker for a payment screenshot that shows what was picked before anything is uploaded:
 * a thumbnail, the file name and size, a "Preview" button (opens the zoomable viewer) and
 * "Remove". The upload itself only happens when the parent form is submitted.
 */
export function ProofFilePicker({
  label = "Payment screenshot (optional)",
  file,
  onChange,
  className,
  maxBytes = 10 * 1024 * 1024,
}: {
  label?: string;
  file: File | null;
  onChange: (file: File | null) => void;
  className?: string;
  maxBytes?: number;
}) {
  const inputId = React.useId();
  const inputRef = React.useRef<HTMLInputElement>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [open, setOpen] = React.useState(false);
  const url = React.useMemo(() => (file ? URL.createObjectURL(file) : null), [file]);
  React.useEffect(() => () => {
    if (url) URL.revokeObjectURL(url);
  }, [url]);
  // Clear the native input whenever the parent clears the file (e.g. after a successful submit).
  React.useEffect(() => {
    if (!file && inputRef.current) inputRef.current.value = "";
  }, [file]);

  const kind = file ? proofKindFromFile(file) : "image";

  const pick = (f: File | null) => {
    setError(null);
    if (!f) return onChange(null);
    const ok = f.type.startsWith("image/") || proofKindFromFile(f) === "pdf";
    if (!ok) {
      setError("Attach an image (JPG, PNG…) or a PDF.");
      if (inputRef.current) inputRef.current.value = "";
      return onChange(null);
    }
    if (f.size > maxBytes) {
      setError(`That file is larger than ${Math.round(maxBytes / 1024 / 1024)} MB.`);
      if (inputRef.current) inputRef.current.value = "";
      return onChange(null);
    }
    onChange(f);
  };

  return (
    <div className={`mb-3 ${className ?? ""}`}>
      <label htmlFor={inputId} className="mb-1 block text-xs font-medium text-muted">
        {label}
      </label>
      <input
        id={inputId}
        ref={inputRef}
        type="file"
        accept="image/*,.pdf,application/pdf"
        onChange={(e) => pick(e.target.files?.[0] ?? null)}
        className={file ? "sr-only" : "block w-full text-sm text-ink file:mr-3 file:rounded file:border file:border-line file:bg-white file:px-3 file:py-1.5 file:text-sm file:font-semibold file:text-navy hover:file:bg-cream"}
      />
      {file && url && (
        <div className="flex items-center gap-3 rounded border border-line bg-white p-2">
          <button
            type="button"
            onClick={() => setOpen(true)}
            className="relative grid h-14 w-14 flex-shrink-0 cursor-zoom-in place-items-center overflow-hidden rounded border border-line bg-cream"
            aria-label="Preview the attached screenshot"
          >
            {kind === "pdf" ? (
              <FileText size={22} className="text-navy" />
            ) : (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={url} alt="" className="h-full w-full object-cover" />
            )}
          </button>
          <div className="min-w-0 flex-1">
            <div className="truncate text-sm font-semibold text-ink" title={file.name}>
              {file.name}
            </div>
            <div className="text-xs text-muted">
              {(file.size / 1024).toFixed(0)} KB · not uploaded yet — check it before you submit
            </div>
          </div>
          <div className="flex flex-shrink-0 gap-1.5">
            <button type="button" onClick={() => setOpen(true)} className="btn btn-sm btn-outline">
              <Eye size={13} /> Preview
            </button>
            <button
              type="button"
              onClick={() => inputRef.current?.click()}
              className="btn btn-sm btn-outline"
              title="Pick a different file"
            >
              Change
            </button>
            <button
              type="button"
              onClick={() => pick(null)}
              className="btn btn-sm btn-outline"
              aria-label="Remove the attached screenshot"
              title="Remove"
            >
              <Trash2 size={13} />
            </button>
          </div>
        </div>
      )}
      {error && <p className="mt-1 text-xs text-error-700">{error}</p>}
      {url && (
        <ProofViewer
          src={url}
          kind={kind}
          title={`Preview — ${file?.name ?? "screenshot"}`}
          open={open}
          onClose={() => setOpen(false)}
        />
      )}
    </div>
  );
}
