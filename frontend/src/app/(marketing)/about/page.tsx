import type { Metadata } from "next";
import { html } from "../_content/about";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'About DhanBoost — Making Credit Calm, Clear & Fair',
  description: 'A better way to borrow for everyday India — transparent, fast and humane.',
  alternates: { canonical: '/about' },
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
