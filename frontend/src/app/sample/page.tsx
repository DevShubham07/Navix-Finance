import type { Metadata } from "next";
import { SampleDashboard } from "./sample-dashboard";

/**
 * /sample — the living style guide for the DhanBoost UI kit (`@/components/kit`) and the
 * theme tokens (`src/styles/theme.css`). Static demo data only; no session, no backend.
 */
export const metadata: Metadata = {
  title: "UI kit — DhanBoost",
  robots: { index: false, follow: false },
};

export default function SamplePage() {
  return <SampleDashboard />;
}
