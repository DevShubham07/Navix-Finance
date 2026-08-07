"use client";

import * as React from "react";

/**
 * Pure-CSS celebration confetti — no canvas, no dependency, ~40 absolutely-positioned strips that
 * fall once and stop.
 *
 * <p>`prefers-reduced-motion` is honoured by rendering nothing at all rather than by freezing the
 * pieces mid-fall: a static scatter of coloured rectangles over the page is noise, not a quieter
 * celebration.
 *
 * <p>`aria-hidden` + `pointer-events-none`: decorative, and it must never sit between the borrower
 * and the Continue button underneath it.
 */
const PIECES = 40;
const COLOURS = ["var(--gold)", "var(--navy)", "var(--success-600)", "var(--gold-dark)"];

export function Confetti() {
  const [show, setShow] = React.useState(false);

  React.useEffect(() => {
    // Read after mount: the media query is client-only, and rendering nothing on the server keeps
    // the markup identical either way.
    const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    setShow(!reduced);
  }, []);

  const pieces = React.useMemo(
    () =>
      Array.from({ length: PIECES }, (_, i) => ({
        left: `${(i * 97) % 100}%`,          // deterministic spread — no hydration mismatch
        delay: `${((i * 7) % 20) / 10}s`,
        duration: `${2.4 + ((i * 13) % 18) / 10}s`,
        colour: COLOURS[i % COLOURS.length],
        tilt: `${((i * 37) % 90) - 45}deg`,
      })),
    [],
  );

  if (!show) return null;

  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 z-50 overflow-hidden">
      {pieces.map((p, i) => (
        <span
          key={i}
          className="dhb-confetti absolute top-[-12px] block h-3 w-1.5 rounded-[1px]"
          style={{
            left: p.left,
            backgroundColor: p.colour,
            animationDelay: p.delay,
            animationDuration: p.duration,
            ["--dhb-tilt" as string]: p.tilt,
          }}
        />
      ))}
      <style>{`
        @keyframes dhb-confetti-fall {
          0%   { transform: translateY(0) rotate(0deg); opacity: 1; }
          100% { transform: translateY(105vh) rotate(var(--dhb-tilt, 45deg)); opacity: 0; }
        }
        .dhb-confetti {
          animation-name: dhb-confetti-fall;
          animation-timing-function: cubic-bezier(0.25, 0.6, 0.5, 1);
          animation-fill-mode: forwards;
          animation-iteration-count: 1;
        }
      `}</style>
    </div>
  );
}
