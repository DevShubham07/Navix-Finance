import type { Metadata } from "next";
import {
  Inter,
  Source_Serif_4,
  Bricolage_Grotesque,
  Hanken_Grotesk,
  IBM_Plex_Mono,
} from "next/font/google";
import "./globals.css";
import { Providers } from "@/components/providers";
import { RouteProgress } from "@/components/app/route-progress";

/**
 * Brand typefaces — Inter (body/UI) + Source Serif 4 (headings), matching the
 * "Classic Corporate" design system. Exposed as CSS variables that
 * globals.css (`--sans` / `--serif`) and tailwind.config (`font-sans` /
 * `font-serif`) both consume.
 */
const inter = Inter({
  subsets: ["latin"],
  weight: ["400", "500", "600", "700"],
  variable: "--font-sans",
  display: "swap",
});

const sourceSerif = Source_Serif_4({
  subsets: ["latin"],
  weight: ["400", "600", "700"],
  variable: "--font-serif",
  display: "swap",
});

/**
 * Marketing typefaces — Bricolage Grotesque (display), Hanken Grotesk (body),
 * IBM Plex Mono (figures). Consumed ONLY inside `.navix-mkt` (marketing-theme.css)
 * via `--font-bricolage` / `--font-hanken` / `--font-plex-mono`, so they never
 * touch the borrower/staff functional app.
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
  title:
    "NAVIX — Instant Personal Loans, Fully Digital | Powered by RBI-Registered NBFC Partners",
  description:
    "NAVIX is a digital lending platform offering instant, fully-digital personal loans in partnership with RBI-registered NBFC lending partners. Paperless process, direct bank disbursal, zero advance fees.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en-IN"
      className={`${inter.variable} ${sourceSerif.variable} ${bricolage.variable} ${hanken.variable} ${plexMono.variable}`}
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
