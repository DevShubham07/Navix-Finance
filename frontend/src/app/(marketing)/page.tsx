import type { Metadata } from "next";
import { html } from "./_content/home";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'DhanBoost — Instant Personal Loans, Fully Digital',
  description: 'Instant personal loans ₹5,000–₹10,00,000, fully online, fairly priced — salary-linked, with a single repayment and no advance fees.',
  alternates: { canonical: '/' },
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
