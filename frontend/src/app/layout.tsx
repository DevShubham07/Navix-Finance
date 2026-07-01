import type { Metadata } from "next";
import {
  Bricolage_Grotesque,
  Hanken_Grotesk,
  IBM_Plex_Mono,
} from "next/font/google";
import "./globals.css";
import { Providers } from "@/components/providers";
import { RouteProgress } from "@/components/app/route-progress";

/**
 * Unified brand typefaces (2026 "calendar" design system) — Bricolage Grotesque
 * (display/headings), Hanken Grotesk (body/UI), IBM Plex Mono (figures). These
 * power BOTH the functional app (globals.css `--serif`/`--sans`/`--mono` +
 * tailwind `font-serif`/`font-sans`/`font-mono`) and the marketing site
 * (`.navix-mkt` consumes `--font-bricolage`/`--font-hanken`/`--font-plex-mono`).
 */
const bricolage = Bricolage_Grotesque({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700", "800"],
  variable: "--font-bricolage",
  display: "swap",
});

const hanken = Hanken_Grotesk({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700", "800"],
  variable: "--font-hanken",
  display: "swap",
});

const plexMono = IBM_Plex_Mono({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-plex-mono",
  display: "swap",
});

export const metadata: Metadata = {
  metadataBase: new URL("https://www.navixfinance.com"),
  title: "NAVIX — Instant Personal Loans, Fully Digital",
  description:
    "NAVIX is a digital lending platform offering instant, fully-digital, salary-linked personal loans. Paperless process, direct bank disbursal, single repayment, zero advance fees.",
  // Site-wide default canonical. Each (marketing) page sets its own self-canonical; a page
  // that omits one would inherit "/" here (deindex risk) — a per-page assertion guards that.
  alternates: { canonical: "/" },
  openGraph: {
    type: "website",
    siteName: "NAVIX",
    locale: "en_IN",
    url: "/",
    title: "NAVIX — Instant Personal Loans, Fully Digital",
    description:
      "Instant, fully-digital, salary-linked personal loans — single repayment, no advance fees.",
  },
  twitter: {
    card: "summary_large_image",
    title: "NAVIX — Instant Personal Loans, Fully Digital",
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
      className={`${bricolage.variable} ${hanken.variable} ${plexMono.variable}`}
    >
      {/* suppressHydrationWarning: browser extensions (screenshot/zoom tools, etc.)
          mutate <body> attributes — e.g. style="zoom:1" — before React hydrates,
          producing a benign server/client attribute mismatch. This silences only
          that top-level attribute diff; it does not affect children. */}
      <body suppressHydrationWarning>
        <RouteProgress />
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
