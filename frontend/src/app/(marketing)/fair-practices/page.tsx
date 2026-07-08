import type { Metadata } from "next";
import { html } from "../_content/fair-practices";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Fair Practices Code — NAVIX',
  description: 'Our commitment to transparent, non-coercive, responsible lending.',
  alternates: { canonical: '/fair-practices' },
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
