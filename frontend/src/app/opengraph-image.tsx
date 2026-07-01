import { ImageResponse } from "next/og";

/**
 * Default Open Graph share image (Next.js App Router convention), 1200×630, in the NAVIX
 * brand palette. Applies site-wide as `og:image`. Pure layout — no external font/asset fetch —
 * so it renders on the edge runtime without file loading. `twitter-image.tsx` re-exports this
 * for an explicit `twitter:image`.
 */
export const runtime = "edge";
export const alt = "NAVIX — Instant personal loans. Fully digital. Fairly priced.";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

export default function Image() {
  return new ImageResponse(
    (
      <div
        style={{
          height: "100%",
          width: "100%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          padding: "80px",
          background: "#0C2540",
          color: "#FDFBF6",
          fontFamily: "sans-serif",
        }}
      >
        <div style={{ fontSize: 96, fontWeight: 800, letterSpacing: "-0.03em", color: "#E9B53A" }}>
          NAVIX
        </div>
        <div style={{ fontSize: 52, fontWeight: 700, marginTop: 24, lineHeight: 1.1 }}>
          Instant personal loans.
        </div>
        <div style={{ fontSize: 52, fontWeight: 700, lineHeight: 1.1 }}>
          Fully digital. Fairly priced.
        </div>
        <div style={{ fontSize: 30, marginTop: 32, opacity: 0.85 }}>
          Salary-linked · single repayment · no advance fees
        </div>
      </div>
    ),
    { ...size },
  );
}
