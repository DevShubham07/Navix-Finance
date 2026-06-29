"use client";

import * as React from "react";

/**
 * A UPI/payment QR thumbnail that enlarges on hover (or keyboard focus) so the user can
 * actually scan it. The thumbnail keeps its inline size via {@link thumbClassName}; on
 * hover a larger, scannable copy pops up above it in a white card. The overlay is
 * `pointer-events-none` so it never blocks the layout, and is mirrored on `focus-within`
 * for keyboard/touch users.
 */
export function ZoomableQr({
  src,
  alt = "UPI QR code",
  thumbClassName,
  wrapperClassName,
  zoomLabel = "Scan to pay",
}: {
  src: string;
  alt?: string;
  /** Classes for the inline thumbnail image (size, border, etc.). */
  thumbClassName?: string;
  /** Extra classes for the wrapper (e.g. `flex-shrink-0` when inside a flex row). */
  wrapperClassName?: string;
  zoomLabel?: string;
}) {
  return (
    <span className={`group relative inline-block ${wrapperClassName ?? ""}`}>
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src={src} alt={alt} tabIndex={0} className={`cursor-zoom-in ${thumbClassName ?? ""}`} />
      {/* Enlarged preview — shown on hover / focus, centred above the thumbnail. */}
      <span className="pointer-events-none absolute bottom-full left-1/2 z-50 mb-2 hidden -translate-x-1/2 group-hover:block group-focus-within:block">
        <span className="block rounded-lg border border-line bg-white p-3 shadow-2xl">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={src} alt="" className="h-60 w-60 max-w-[70vw] object-contain" />
          <span className="mt-1.5 block text-center text-[11px] font-semibold text-muted">{zoomLabel}</span>
        </span>
      </span>
    </span>
  );
}
