"use client";

import * as React from "react";
import type { DashView } from "@/lib/api/applications";
import type { DashTabProps } from "./tab-props";
import { BusinessSnapshot } from "./tabs/business-snapshot";
import { CalendarTab } from "./tabs/calendar-tab";
import { CollectionAllocationTab } from "./tabs/collection-allocation-tab";
import { CompaniesTab } from "./tabs/companies-tab";
import { MapTab } from "./tabs/map-tab";
import { CreditReportTab, SalesReportTab } from "./tabs/staff-report-tab";
import { TeamPerformance } from "./tabs/team-performance";
import { AccountantView } from "./views/accountant-view";
import { CollectionView } from "./views/collection-view";
import { CreditView } from "./views/credit-view";
import { DisbursementView } from "./views/disbursement-view";
import { TelecallerView } from "./views/telecaller-view";

/**
 * THE registry the dashboard page renders from.
 *
 *  - `ADMIN_TABS`          order + labels of the Admin overview's tab bar
 *  - `ADMIN_TAB_REGISTRY`  tab id -> component rendered under the tab bar
 *  - `ROLE_VIEW_REGISTRY`  non-admin view id -> component rendered for that role's view
 *
 * Every component receives {@link DashTabProps} (`params`, `open` the records drawer, `realAdmin`,
 * `periodLabel`).
 */

export type AdminTabId =
  | "snapshot"
  | "team"
  | "calendar"
  | "map"
  | "companies"
  | "credit-report"
  | "sales-report"
  | "allocation";

export const ADMIN_TABS: { id: AdminTabId; label: string }[] = [
  { id: "snapshot", label: "Business Snapshot" },
  { id: "team", label: "Team & Performance" },
  { id: "calendar", label: "Calendar" },
  { id: "map", label: "Map" },
  { id: "companies", label: "Company-wise" },
  { id: "credit-report", label: "Credit Executive Report" },
  { id: "sales-report", label: "Sales Ops Report" },
  { id: "allocation", label: "Collection Allocation" },
];

export const ADMIN_TAB_REGISTRY: Record<AdminTabId, React.ComponentType<DashTabProps>> = {
  snapshot: BusinessSnapshot,
  team: TeamPerformance,
  calendar: CalendarTab,
  map: MapTab,
  companies: CompaniesTab,
  "credit-report": CreditReportTab,
  "sales-report": SalesReportTab,
  allocation: CollectionAllocationTab,
};

export const ROLE_VIEW_REGISTRY: Record<Exclude<DashView, "ADMIN">, React.ComponentType<DashTabProps>> = {
  CREDIT_HEAD: CreditView,
  CREDIT_EXECUTIVE: CreditView,
  COLLECTION_HEAD: CollectionView,
  COLLECTION_EXECUTIVE: CollectionView,
  TELECALLER: TelecallerView,
  DISBURSEMENT_HEAD: DisbursementView,
  ACCOUNTANT: AccountantView,
};
