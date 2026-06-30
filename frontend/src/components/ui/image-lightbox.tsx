"use client";

import * as React from "react";
import { createPortal } from "react-dom";
import { X, ZoomIn } from "lucide-react";

/**
 * A clickable image thumbnail that opens a full-screen lightbox on click/tap — large and
 * scannable on both mobile and laptop. The lightbox scales the image to fit the viewport,
 * shows a clear close (✕) button, and dismisses on backdrop click or Escape. This replaces
 * hover-only zoom, which is unusable on touch devices and too small on desktop.
 */
export function ZoomableImage({
  src,
  alt = "",
  caption,
  thumbClassName,
  wrapperClassName,
}: {
  src: string;
  alt?: string;
  /** Caption shown under the enlarged image (e.g. "Scan to pay with any UPI app"). */
  caption?: string;
  /** Classes for the inline thumbnail image (size, border, etc.). */
  thumbClassName?: string;
  /** Extra classes for the wrapping button (e.g. `flex-shrink-0` in a flex row). */
  wrapperClassName?: string;
}) {
  const [open, setOpen] = React.useState(false);
  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className={`group relative inline-block cursor-zoom-in leading-none ${wrapperClassName ?? ""}`}
        aria-label={alt ? `Enlarge ${alt}` : "Enlarge image"}
      >
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={src} alt={alt} className={thumbClassName} />
        {/* Affordance: a small zoom badge so it reads as tappable (no hover needed on touch). */}
        <span className="pointer-events-none absolute bottom-1 right-1 grid h-6 w-6 place-items-center rounded-full bg-navy/80 text-white shadow-sm transition group-hover:bg-navy">
          <ZoomIn size={13} />
        </span>
      </button>
      <ImageLightbox src={src} alt={alt} caption={caption} open={open} onClose={() => setOpen(false)} />
    </>
  );
}

/** The full-screen image viewer. Controlled — render it yourself for a custom trigger. */
export function ImageLightbox({
  src,
  alt = "",
  caption,
  open,
  onClose,
}: {
  src: string;
  alt?: string;
  caption?: string;
  open: boolean;
  onClose: () => void;
}) {
  const [mounted, setMounted] = React.useState(false);
  React.useEffect(() => setMounted(true), []);

  React.useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    // Lock background scroll while the lightbox is open.
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = prevOverflow;
    };
  }, [open, onClose]);

  if (!open || !mounted) return null;

  return createPortal(
    <div
      className="fixed inset-0 z-[200] flex items-center justify-center bg-black/85 p-4 sm:p-8"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
      aria-label={alt || "Image preview"}
    >
      {/* Close button — fixed to the viewport corner so it's always reachable, with a 44px
          tap target for mobile. */}
      <button
        type="button"
        onClick={onClose}
        aria-label="Close"
        className="fixed right-3 top-3 z-10 grid h-11 w-11 place-items-center rounded-full bg-white/95 text-navy shadow-lg transition hover:bg-white active:scale-95 sm:right-5 sm:top-5"
      >
        <X size={22} />
      </button>
      <figure
        className="flex max-h-full max-w-full flex-col items-center"
        onClick={(e) => e.stopPropagation()}
      >
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={src}
          alt={alt}
          className="max-h-[82vh] max-w-[92vw] rounded-lg bg-white object-contain shadow-2xl"
        />
        {caption ? (
          <figcaption className="mt-3 rounded-full bg-white/90 px-4 py-1.5 text-center text-sm font-semibold text-navy">
            {caption}
          </figcaption>
        ) : null}
      </figure>
    </div>,
    document.body,
  );
}
