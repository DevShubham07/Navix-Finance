import type { Metadata } from "next";
import { html } from "../_content/terms";
import { MarketingHtml } from "@/components/site/marketing-html";

export const metadata: Metadata = {
  title: 'Terms & Conditions — NAVIX',
  description: 'The terms governing your use of the NAVIX platform.',
};

export default function Page() {
  return <MarketingHtml html={html} />;
}
