import type { ApplicationView, CustomerDetail } from "@/lib/api/applications";

/** What every tab body of the customer pop-up / full customer page receives. */
export interface TabCtx {
  detail: CustomerDetail;
  customerId: number;
  applicationId: number | null;
  app: ApplicationView | null;
  onChanged: () => void;
  onOpenApplication?: (id: number) => void;
  onTabChange?: (key: string) => void;
}
