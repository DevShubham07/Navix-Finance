import type { Metadata } from "next";
import { CareersPage } from "@/components/site/landing/careers";

export const metadata: Metadata = {
  title: 'Careers: Build Fair Finance With Us | DhanBoost',
  description: 'Come build with us: open roles across engineering, design and risk.',
  alternates: { canonical: '/careers' },
};

export default function Page() {
  return <CareersPage />;
}
