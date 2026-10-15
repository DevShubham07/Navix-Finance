import type { Metadata } from "next";
import { html } from "../_content/refund-policy";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Refund & Cancellation Policy — DhanBoost',
  description: 'When a refund is due, how to request one, and how long it takes.',
  alternates: { canonical: '/refund-policy' },
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
