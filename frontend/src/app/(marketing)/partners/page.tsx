import type { Metadata } from "next";
import { html } from "../_content/partners";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Lending Partners — RBI-Registered NBFCs — NAVIX',
  description: 'Loans by RBI-registered NBFC partners you can trust.',
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
