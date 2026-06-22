import type { Metadata } from "next";
import { Inter, Source_Serif_4 } from "next/font/google";
import "./globals.css";
import { Providers } from "@/components/providers";

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
    <html lang="en-IN" className={`${inter.variable} ${sourceSerif.variable}`}>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
