import type { Metadata } from "next";
import { html } from "../_content/contact";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Contact Us — NAVIX',
  description: 'Let\'s talk — send us a message and we\'ll get back to you.',
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
