import type { Metadata } from "next";
import { html } from "../_content/careers";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Careers — Build Fair Finance With Us — NAVIX',
  description: 'Come build with us — open roles across engineering, design and risk.',
  alternates: { canonical: '/careers' },
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
