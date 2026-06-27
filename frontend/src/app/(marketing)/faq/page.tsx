import type { Metadata } from "next";
import { html } from "../_content/faq";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Frequently Asked Questions — NAVIX',
  description: 'Answers on applications, rates, repayments and grievance redressal.',
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
