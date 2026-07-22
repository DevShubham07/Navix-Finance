import type { Metadata } from "next";
import { Inter } from "next/font/google";
import "./globals.css";
import { Providers } from "@/components/providers";
import { RouteProgress } from "@/components/app/route-progress";
import { GoogleAnalytics } from "@/components/analytics/google-analytics";

/**
 * Unified brand typeface — Inter powers everything (display/headings, body/UI,
 * and figures). It backs BOTH the functional app (globals.css
 * `--serif`/`--sans`/`--mono` + tailwind `font-serif`/`font-sans`/`font-mono`)
 * and the marketing site (`.navix-mkt` consumes `--font-inter`). Inter's
 * tabular figures (`tnum`) keep ledger amounts column-aligned.
 */
const inter = Inter({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700", "800"],
  variable: "--font-inter",
  // `block`, not `swap`: the marketing hero animates its headline word-by-word on
  // promoted `will-change` layers. With `swap`, the first line can paint in the
  // metric fallback before Inter loads and the promoted layer never repaints on
  // swap-in — leaving line 1 in a different (Arial-like) face than the rest.
  // `block` makes every line wait for Inter; the entrance animation already keeps
  // the words invisible for ~120ms, so this adds no perceptible delay.
  display: "block",
});

export const metadata: Metadata = {
  metadataBase: new URL("https://www.dhanboost.com"),
  title: "DhanBoost — Instant Personal Loans, Fully Digital",
  description:
    "DhanBoost is a digital lending platform offering instant, fully-digital, salary-linked personal loans. Paperless process, direct bank disbursal, single repayment, zero advance fees.",
  // Site-wide default canonical. Each (marketing) page sets its own self-canonical; a page
  // that omits one would inherit "/" here (deindex risk) — a per-page assertion guards that.
  alternates: { canonical: "/" },
  // Google Search Console verification. Renders <meta name="google-site-verification"> only when
  // GOOGLE_SITE_VERIFICATION is set (undefined → omitted). Set it in Vercel env (and .env.local
  // locally) to the token GSC gives on the URL-prefix property. The token is not secret.
  verification: { google: process.env.GOOGLE_SITE_VERIFICATION },
  openGraph: {
    type: "website",
    siteName: "DhanBoost",
    locale: "en_IN",
    url: "/",
    title: "DhanBoost — Instant Personal Loans, Fully Digital",
    description:
      "Instant, fully-digital, salary-linked personal loans — single repayment, no advance fees.",
  },
  twitter: {
    card: "summary_large_image",
    title: "DhanBoost — Instant Personal Loans, Fully Digital",
    description:
      "Instant, fully-digital, salary-linked personal loans — single repayment, no advance fees.",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en-IN"
      className={inter.variable}
    >
      {/* suppressHydrationWarning: browser extensions (screenshot/zoom tools, etc.)
          mutate <body> attributes — e.g. style="zoom:1" — before React hydrates,
          producing a benign server/client attribute mismatch. This silences only
          that top-level attribute diff; it does not affect children. */}
      <body suppressHydrationWarning>
        <GoogleAnalytics />
        <RouteProgress />
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
