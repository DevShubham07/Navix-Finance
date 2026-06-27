import type { Metadata } from "next";
import { html } from "../_content/support";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Help & Support — NAVIX',
  description: 'We\'re here to help — live chat, email and phone support.',
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
