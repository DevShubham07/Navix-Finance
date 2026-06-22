import { SiteShell } from "@/components/site";

/** Public marketing area — full site chrome (utility bar, nav, footer). */
export default function MarketingLayout({ children }: { children: React.ReactNode }) {
  return <SiteShell>{children}</SiteShell>;
}
