import { UtilityBar } from "./utility-bar";
import { SiteHeader } from "./site-header";
import { FraudAlert } from "./fraud-alert";
import { SiteFooter } from "./site-footer";
import { RevealInit } from "./reveal-init";

/** Full public-marketing chrome: utility bar → header → fraud alert → page → footer. */
export function SiteShell({ children }: { children: React.ReactNode }) {
  return (
    <>
      <RevealInit />
      <UtilityBar />
      <SiteHeader />
      <FraudAlert />
      <main>{children}</main>
      <SiteFooter />
    </>
  );
}
