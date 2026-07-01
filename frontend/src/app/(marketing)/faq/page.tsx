import type { Metadata } from "next";
import { html } from "../_content/faq";
import { MarketingHtml } from "@/components/site/marketing-html";
import { FaqSchema } from "@/components/site/faq-schema";

export const metadata: Metadata = {
  title: 'Frequently Asked Questions — NAVIX',
  description: 'Answers on applications, rates, repayments and grievance redressal.',
  alternates: { canonical: '/faq' },
};

export default function Page() {
  return (
    <>
      <FaqSchema />
      <MarketingHtml html={html} />
    </>
  );
}
