import type { Metadata } from "next";
import { html } from "../_content/privacy";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Privacy Policy — NAVIX',
  description: 'How NAVIX collects, uses, shares and protects your information.',
  alternates: { canonical: '/privacy' },
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
