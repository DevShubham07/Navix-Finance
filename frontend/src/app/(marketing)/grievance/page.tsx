import type { Metadata } from "next";
import { html } from "../_content/grievance";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Grievance Redressal — NAVIX',
  description: 'How to raise a complaint and reach our Grievance Officer.',
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
