import type { Metadata } from "next";
import { HowItWorksPage } from "@/components/site/landing/how-it-works";

export const metadata: Metadata = {
  title: 'How It Works | DhanBoost',
  description: 'From application to your account in four simple steps, fully digital.',
  alternates: { canonical: '/how-it-works' },
};

export default function Page() {
  return <HowItWorksPage />;
}
