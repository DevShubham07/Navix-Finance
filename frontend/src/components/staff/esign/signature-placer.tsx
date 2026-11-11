"use client";

import * as React from "react";
import { Copy, Loader2, X } from "lucide-react";
import type { PDFDocumentProxy } from "pdfjs-dist";
import {
  MAX_BOXES_PER_SIGNER,
  dropBox,
  moveBox,
  pxToPt,
  repeatOnAllPages,
  resizeBox,
  type Box,
  type PageSize,
} from "@/lib/esign/placement";
import type { SignerForm } from "@/lib/esign/validate-esign-form";

/** One look per signer slot (max 5), all from the theme palette. Full class names so Tailwind keeps them. */
const SIGNER_STYLES = [
  { chip: "border-gold bg-gold-50 text-gold-dark", box: "border-gold bg-gold/20 text-gold-dark", handle: "bg-gold" },
  { chip: "border-info-600 bg-info-50 text-info-700", box: "border-info-600 bg-info-600/15 text-info-700", handle: "bg-info-600" },
  { chip: "border-warning-600 bg-warning-50 text-warning-700", box: "border-warning-600 bg-warning-600/15 text-warning-700", handle: "bg-warning-600" },
  { chip: "border-error-600 bg-error-50 text-error-700", box: "border-error-600 bg-error-600/15 text-error-700", handle: "bg-error-600" },
  { chip: "border-navy bg-navy-tint text-navy", box: "border-navy bg-navy/15 text-navy", handle: "bg-navy" },
] as const;

const styleFor = (i: number) => SIGNER_STYLES[i % SIGNER_STYLES.length];
const labelFor = (s: SignerForm, i: number) => s.name.trim() || `Signer ${i + 1}`;

interface Sel {
  s: number;
  i: number;
}

interface Props {
  file: File;
  signers: SignerForm[];
  onBoxesChange: (signerIndex: number, boxes: Box[]) => void;
}

/** Renders every page of the chosen PDF and lets the admin drop, move, resize and delete signature boxes. */
export function SignaturePlacer({ file, signers, onBoxesChange }: Props) {
  const [pdf, setPdf] = React.useState<PDFDocumentProxy | null>(null);
  const [sizes, setSizes] = React.useState<PageSize[]>([]);
  const [error, setError] = React.useState<string | null>(null);
  const [active, setActive] = React.useState(0);
  const [sel, setSel] = React.useState<Sel | null>(null);
  const [width, setWidth] = React.useState(0);
  const wrapRef = React.useRef<HTMLDivElement>(null);

  // Load the document client-side only (pdf.js touches the DOM at import time).
  React.useEffect(() => {
    let cancelled = false;
    let doc: PDFDocumentProxy | null = null;
    setPdf(null);
    setSizes([]);
    setError(null);
    setSel(null);
    (async () => {
      try {
        const pdfjs = await import("pdfjs-dist");
        pdfjs.GlobalWorkerOptions.workerSrc = new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url).toString();
        const task = pdfjs.getDocument({ data: new Uint8Array(await file.arrayBuffer()) });
        doc = await task.promise;
        const out: PageSize[] = [];
        for (let n = 1; n <= doc.numPages; n++) {
          // scale 1 viewport = PDF points with the page rotation applied
          const vp = (await doc.getPage(n)).getViewport({ scale: 1 });
          out.push({ width: vp.width, height: vp.height });
        }
        if (cancelled) return void doc.destroy();
        setPdf(doc);
        setSizes(out);
      } catch {
        if (!cancelled) setError("This PDF could not be shown. It may be corrupt or password-protected.");
      }
    })();
    return () => {
      cancelled = true;
      void doc?.destroy();
    };
  }, [file]);

  // Responsive: pages render at the container width.
  React.useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setWidth(Math.floor(el.clientWidth)));
    ro.observe(el);
    setWidth(Math.floor(el.clientWidth));
    return () => ro.disconnect();
  }, []);

  const activeIdx = Math.min(active, Math.max(signers.length - 1, 0));
  const selBox = sel ? signers[sel.s]?.boxes[sel.i] : undefined;

  const setBox = (s: number, i: number, b: Box) =>
    onBoxesChange(s, signers[s].boxes.map((x, j) => (j === i ? b : x)));

  const removeBox = (s: number, i: number) => {
    onBoxesChange(s, signers[s].boxes.filter((_, j) => j !== i));
    setSel(null);
  };

  const addBox = (page: number, xPt: number, yPt: number) => {
    const s = signers[activeIdx];
    if (!s || s.boxes.length >= MAX_BOXES_PER_SIGNER) return;
    onBoxesChange(activeIdx, [...s.boxes, dropBox(page, xPt, yPt, sizes[page - 1])]);
    setSel({ s: activeIdx, i: s.boxes.length });
  };

  const repeat = () => {
    if (!sel || !selBox) return;
    const room = MAX_BOXES_PER_SIGNER - signers[sel.s].boxes.length;
    const added = repeatOnAllPages(signers[sel.s].boxes, selBox, sizes).slice(0, Math.max(room, 0));
    if (added.length) onBoxesChange(sel.s, [...signers[sel.s].boxes, ...added]);
  };

  return (
    <div className="rounded border border-line p-3">
      <p className="mb-2 text-sm font-semibold text-ink">Signature boxes</p>
      <p className="mb-3 text-xs text-muted">
        Pick a signer, then click on a page to drop a box. Drag a box to move it, drag its corner to resize,
        and use &times; (or Delete) to remove it.
      </p>

      <div className="mb-3 flex flex-wrap items-center gap-2">
        {signers.map((s, i) => (
          <button
            key={i}
            type="button"
            aria-pressed={i === activeIdx}
            onClick={() => setActive(i)}
            className={`rounded-full border px-3 py-1 text-xs font-semibold ${styleFor(i).chip} ${
              i === activeIdx ? "ring-2 ring-offset-1 ring-navy" : "opacity-70 hover:opacity-100"
            }`}
          >
            {labelFor(s, i)} ({s.boxes.length})
          </button>
        ))}
        <button
          type="button"
          className="btn btn-sm btn-outline ml-auto"
          disabled={!selBox || sel?.s !== activeIdx || sizes.length < 2}
          onClick={repeat}
        >
          <Copy size={13} /> Repeat on every page
        </button>
      </div>

      <div ref={wrapRef} className="max-h-[60vh] overflow-y-auto rounded bg-grey-100 p-2">
        {error ? (
          <p className="p-4 text-sm text-error-700">{error}</p>
        ) : !pdf || width === 0 ? (
          <div className="flex items-center gap-2 p-4 text-sm text-muted">
            <Loader2 size={14} className="animate-spin" /> Loading pages…
          </div>
        ) : (
          sizes.map((size, idx) => (
            <PageView
              key={idx}
              pdf={pdf}
              pageNumber={idx + 1}
              size={size}
              // 16px = the p-2 gutter on both sides of the scroller
              scale={Math.max(width - 16, 100) / size.width}
              signers={signers}
              sel={sel}
              onSelect={setSel}
              onAdd={(x, y) => addBox(idx + 1, x, y)}
              onChange={setBox}
              onRemove={removeBox}
            />
          ))
        )}
      </div>
    </div>
  );
}

interface PageViewProps {
  pdf: PDFDocumentProxy;
  pageNumber: number;
  size: PageSize;
  scale: number;
  signers: SignerForm[];
  sel: Sel | null;
  onSelect: (s: Sel | null) => void;
  onAdd: (xPt: number, yPt: number) => void;
  onChange: (s: number, i: number, b: Box) => void;
  onRemove: (s: number, i: number) => void;
}

function PageView({ pdf, pageNumber, size, scale, signers, sel, onSelect, onAdd, onChange, onRemove }: PageViewProps) {
  const canvasRef = React.useRef<HTMLCanvasElement>(null);
  const drag = React.useRef<{ mode: "move" | "resize"; x: number; y: number; orig: Box } | null>(null);

  React.useEffect(() => {
    let task: { cancel: () => void; promise: Promise<unknown> } | null = null;
    let dead = false;
    (async () => {
      const page = await pdf.getPage(pageNumber);
      const canvas = canvasRef.current;
      if (dead || !canvas) return;
      const dpr = window.devicePixelRatio || 1;
      const vp = page.getViewport({ scale: scale * dpr });
      canvas.width = Math.floor(vp.width);
      canvas.height = Math.floor(vp.height);
      const ctx = canvas.getContext("2d");
      if (!ctx) return;
      task = page.render({ canvasContext: ctx, viewport: vp });
      await task.promise.catch(() => undefined); // a cancelled render rejects; that is expected
    })();
    return () => {
      dead = true;
      task?.cancel();
    };
  }, [pdf, pageNumber, scale]);

  const onPageClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.target !== e.currentTarget && e.target !== canvasRef.current) return;
    const r = e.currentTarget.getBoundingClientRect();
    onAdd(pxToPt(e.clientX - r.left, scale), pxToPt(e.clientY - r.top, scale));
  };

  const startDrag = (e: React.PointerEvent, mode: "move" | "resize", s: number, i: number, b: Box) => {
    e.stopPropagation();
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { mode, x: e.clientX, y: e.clientY, orig: b };
    onSelect({ s, i });
  };

  const onDrag = (e: React.PointerEvent, s: number, i: number) => {
    const d = drag.current;
    if (!d) return;
    const dx = pxToPt(e.clientX - d.x, scale);
    const dy = pxToPt(e.clientY - d.y, scale);
    onChange(s, i, d.mode === "move" ? moveBox(d.orig, dx, dy, size) : resizeBox(d.orig, dx, dy, size));
  };

  const endDrag = (e: React.PointerEvent) => {
    drag.current = null;
    if (e.currentTarget.hasPointerCapture(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId);
  };

  const onKey = (e: React.KeyboardEvent, s: number, i: number, b: Box) => {
    const step = e.shiftKey ? 10 : 1;
    const move = (dx: number, dy: number) => {
      e.preventDefault();
      onChange(s, i, moveBox(b, dx, dy, size));
    };
    if (e.key === "Delete" || e.key === "Backspace") {
      e.preventDefault();
      onRemove(s, i);
    } else if (e.key === "ArrowLeft") move(-step, 0);
    else if (e.key === "ArrowRight") move(step, 0);
    else if (e.key === "ArrowUp") move(0, -step);
    else if (e.key === "ArrowDown") move(0, step);
  };

  return (
    <div className="mb-3 last:mb-0">
      <div className="mb-1 text-xs text-muted">Page {pageNumber}</div>
      <div
        className="relative cursor-crosshair overflow-hidden bg-white shadow-sm"
        style={{ width: size.width * scale, height: size.height * scale }}
        onClick={onPageClick}
      >
        <canvas ref={canvasRef} className="block h-full w-full" />
        {signers.map((signer, s) =>
          signer.boxes.map((b, i) =>
            b.page !== pageNumber ? null : (
              <div
                key={`${s}-${i}`}
                role="group"
                tabIndex={0}
                aria-label={`Signature box for ${labelFor(signer, s)}, page ${pageNumber}. Arrow keys move, Delete removes.`}
                onPointerDown={(e) => startDrag(e, "move", s, i, b)}
                onPointerMove={(e) => onDrag(e, s, i)}
                onPointerUp={endDrag}
                onPointerCancel={endDrag}
                onKeyDown={(e) => onKey(e, s, i, b)}
                onFocus={() => onSelect({ s, i })}
                className={`absolute cursor-move touch-none select-none border-2 text-xs font-semibold outline-none focus-visible:ring-2 focus-visible:ring-navy ${styleFor(s).box} ${
                  sel?.s === s && sel.i === i ? "ring-2 ring-navy" : ""
                }`}
                style={{ left: b.x * scale, top: b.y * scale, width: b.width * scale, height: b.height * scale }}
              >
                <span className="pointer-events-none block truncate px-1 py-0.5">{labelFor(signer, s)}</span>
                <button
                  type="button"
                  aria-label={`Delete signature box for ${labelFor(signer, s)}`}
                  onPointerDown={(e) => e.stopPropagation()}
                  onClick={(e) => {
                    e.stopPropagation();
                    onRemove(s, i);
                  }}
                  className="absolute right-0.5 top-0.5 rounded bg-white/90 p-0.5 text-ink hover:bg-white"
                >
                  <X size={12} />
                </button>
                <span
                  aria-hidden
                  onPointerDown={(e) => startDrag(e, "resize", s, i, b)}
                  onPointerMove={(e) => onDrag(e, s, i)}
                  onPointerUp={endDrag}
                  onPointerCancel={endDrag}
                  className={`absolute bottom-0 right-0 h-4 w-4 cursor-nwse-resize touch-none ${styleFor(s).handle}`}
                />
              </div>
            ),
          ),
        )}
      </div>
    </div>
  );
}
