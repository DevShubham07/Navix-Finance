import type { Metadata } from "next";
import { html } from "../_content/blog";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Resources — Money, Made Clearer — NAVIX',
  description: 'Guides on KFS, credit scores, APR vs flat rate and borrowing wisely.',
  alternates: { canonical: '/blog' },
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
