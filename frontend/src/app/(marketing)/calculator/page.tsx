import type { Metadata } from "next";
import { html } from "../_content/calculator";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Loan Calculator & Transparent Rates — NAVIX',
  description: 'See your exact repayment before you borrow. Transparent, upfront, fair pricing.',
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
