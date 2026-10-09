export interface BankAnalysisView {
  status: "ANALYSED" | "PENDING" | "FAILED";
  bank: string;
  accounts: {
    maskedNumber: string;
    type: string;
    txnCount: number;
    status: string;
    ifsc?: string;
    micr?: string;
    facility?: string;
    branch?: string;
    periodFrom?: string;
    periodTo?: string;
    openingBalancePaise?: number;
    closingBalancePaise?: number;
  }[];
  salary: {
    verdict: "ACCEPT" | "REVIEW" | "REJECT";
    rule: string;
    primarySource: string;
    consecutiveMonths: number;
    minSalaryPaise: number;
    uniqueCredits: number;
    employerOnStatement?: string;
    analysedAt: string;
  } | null;
  excelUrl?: string;
}

export const bankAnalysisApi = {
  // Stub until the bank-statement analyser endpoint lands: swap this body for
  // `bff<BankAnalysisView>(...)` and the Banking tab needs no change.
  get: async (_customerId: number): Promise<BankAnalysisView | null> => null,
};
