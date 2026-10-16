/**
 * The Banking tab's bank-statement-analysis seam. The types and calls live in `applications.ts`
 * beside every other staff API; this module stays as the import path the tab (and the BRE data
 * hook) already use.
 */
export { bankAnalysisApi, type BankAnalysisAccount, type BankAnalysisView } from "@/lib/api/applications";
