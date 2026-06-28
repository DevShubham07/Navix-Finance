import type { Metadata } from "next";
import { html } from "./_content/home";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'NAVIX — Instant Personal Loans, Fully Digital',
  description: 'Instant personal loans ₹5,000–₹1,00,000, fully online, fairly priced — salary-linked, with a single repayment and no advance fees.',
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
