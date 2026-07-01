import type { Metadata } from "next";
import { html } from "../_content/reviews";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Reviews — Borrowers In Their Own Words — NAVIX',
  description: 'Where people talk about us — real borrower reviews.',
  alternates: { canonical: '/reviews' },
  // TEMPORARY noindex: the aggregate review stats here are unverified. Remove this line (and add
  // /reviews back to sitemap.ts) once the numbers are substantiated or replaced (Track B: B4).
  robots: { index: false, follow: true },
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
