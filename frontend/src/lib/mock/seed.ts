import { buildCostBreakdown, dueDateFromSalary, eligibleLimit } from "@/lib/calc/loan-math";
import { STAFF_PERSONAS } from "@/lib/mock/session";
import type {
  ApplicationRecord,
  BlocklistEntry,
  CollectionsCaseRecord,
  InviteRecord,
  KycSnapshot,
  RiskCategory,
  StaffMember,
} from "@/lib/mock/types";

const iso = (d: Date) => d.toISOString();
const daysAgo = (n: number) => iso(new Date(Date.now() - n * 864e5));

const ALL_PASS: KycSnapshot = {
  pan: "PASSED",
  aadhaar: "PASSED",
  selfie: "PASSED",
  address: "PASSED",
  bank: "PASSED",
  overall: "PASSED",
};

function makeLoanRecord(id: string, principal: number, salaryDay: number, disbursedDaysAgo: number) {
  const disbursedOn = new Date(Date.now() - disbursedDaysAgo * 864e5);
  const due = dueDateFromSalary({ disbursedOn, salaryDay });
  const tenureDays = Math.max(1, Math.round((due.getTime() - disbursedOn.getTime()) / 864e5));
  const breakdown = buildCostBreakdown(principal, tenureDays);
  return {
    id,
    principal,
    costBreakdown: breakdown,
    dueDate: iso(due),
    disbursedAt: iso(disbursedOn),
    status: "ACTIVE" as const,
    repayments: [],
    outstanding: breakdown.totalRepayable,
  };
}

function app(partial: Partial<ApplicationRecord> & Pick<ApplicationRecord, "id" | "applicantName" | "monthlySalary" | "stage" | "riskCategory">): ApplicationRecord {
  const salary = partial.monthlySalary;
  const limit = eligibleLimit(salary);
  const requested = partial.requestedAmount ?? limit;
  const salaryDay = partial.salaryDay ?? 30;
  return {
    applicantId: partial.id,
    mobile: "98765 0" + Math.floor(10000 + Math.random() * 89999),
    email: partial.applicantName.toLowerCase().replace(/\s+/g, ".") + "@example.com",
    panMasked: "XXXXX" + Math.floor(1000 + Math.random() * 8999) + "X",
    employer: partial.employer ?? "Infosys Ltd",
    designation: partial.designation ?? "Senior Associate",
    uan: "1001" + Math.floor(10000000 + Math.random() * 8999999),
    salaryDay,
    requestedAmount: requested,
    eligibleLimit: limit,
    creditScore: partial.creditScore ?? 740,
    kyc: partial.kyc ?? ALL_PASS,
    coApplicantRequired: partial.riskCategory === "C" || partial.riskCategory === "D",
    bank: partial.bank ?? {
      holderName: partial.applicantName,
      accountMasked: "XXXX XXXX " + Math.floor(1000 + Math.random() * 8999),
      ifsc: "HDFC0001234",
      bankName: "HDFC Bank",
      pennyDropVerified: false,
    },
    approvalTrail: partial.approvalTrail ?? [],
    createdAt: partial.createdAt ?? daysAgo(2),
    updatedAt: partial.updatedAt ?? daysAgo(1),
    ...partial,
  } as ApplicationRecord;
}

export function seedApplications(): ApplicationRecord[] {
  return [
    app({
      id: "APP-2041",
      applicantName: "Aarav Sharma",
      employer: "Infosys Ltd",
      designation: "Senior Software Engineer",
      monthlySalary: 84000,
      salaryDay: 30,
      riskCategory: "A",
      creditScore: 781,
      stage: "KYC_REVIEW",
      kyc: { pan: "PASSED", aadhaar: "PASSED", selfie: "MANUAL_REVIEW", address: "PASSED", bank: "PASSED", overall: "MANUAL_REVIEW" },
      createdAt: daysAgo(0),
    }),
    app({
      id: "APP-2042",
      applicantName: "Diya Patel",
      employer: "TCS",
      designation: "Business Analyst",
      monthlySalary: 62000,
      salaryDay: 1,
      riskCategory: "B",
      creditScore: 728,
      stage: "CREDIT_QUEUE",
      createdAt: daysAgo(1),
    }),
    app({
      id: "APP-2043",
      applicantName: "Kabir Singh",
      employer: "Wipro",
      designation: "Project Lead",
      monthlySalary: 110000,
      salaryDay: 28,
      riskCategory: "A",
      creditScore: 805,
      stage: "CREDIT_REVIEW",
      assignedExecutiveId: "staff-credit_executive",
      assignedExecutiveName: STAFF_PERSONAS.CREDIT_EXECUTIVE.name,
    }),
    app({
      id: "APP-2044",
      applicantName: "Ishaan Verma",
      employer: "Flipkart",
      designation: "Operations Manager",
      monthlySalary: 95000,
      salaryDay: 30,
      riskCategory: "C",
      creditScore: 671,
      stage: "CREDIT_DECISION",
      assignedExecutiveId: "staff-credit_executive",
      assignedExecutiveName: STAFF_PERSONAS.CREDIT_EXECUTIVE.name,
      recommendation: {
        decision: "APPROVE",
        notes: "Stable salary credits; thin bureau history — recommend at reduced limit with co-applicant.",
        by: STAFF_PERSONAS.CREDIT_EXECUTIVE.name,
        at: daysAgo(0),
      },
      approvalTrail: [
        {
          id: "TRAIL-1",
          actorId: "staff-credit_executive",
          actorName: STAFF_PERSONAS.CREDIT_EXECUTIVE.name,
          role: "CREDIT_EXECUTIVE",
          action: "RECOMMEND",
          notes: "Recommend approval at reduced limit.",
          createdAt: daysAgo(0),
        },
      ],
    }),
    app({
      id: "APP-2045",
      applicantName: "Ananya Reddy",
      employer: "Accenture",
      designation: "Consultant",
      monthlySalary: 73000,
      salaryDay: 25,
      riskCategory: "B",
      creditScore: 752,
      stage: "DISBURSEMENT",
      assignedExecutiveName: STAFF_PERSONAS.CREDIT_EXECUTIVE.name,
      bank: {
        holderName: "Ananya Reddy",
        accountMasked: "XXXX XXXX 8821",
        ifsc: "ICIC0004421",
        bankName: "ICICI Bank",
        pennyDropVerified: true,
      },
      approvalTrail: [
        { id: "T1", actorId: "staff-credit_executive", actorName: STAFF_PERSONAS.CREDIT_EXECUTIVE.name, role: "CREDIT_EXECUTIVE", action: "RECOMMEND", createdAt: daysAgo(2) },
        { id: "T2", actorId: "staff-credit_head", actorName: STAFF_PERSONAS.CREDIT_HEAD.name, role: "CREDIT_HEAD", action: "APPROVE", notes: "Approved at ₹18,000.", createdAt: daysAgo(1) },
      ],
    }),
    app({
      id: "APP-2046",
      applicantName: "Vivaan Gupta",
      employer: "Amazon",
      designation: "SDE II",
      monthlySalary: 128000,
      salaryDay: 30,
      riskCategory: "A",
      creditScore: 792,
      stage: "ACCOUNTING",
      bank: {
        holderName: "Vivaan Gupta",
        accountMasked: "XXXX XXXX 3310",
        ifsc: "HDFC0000123",
        bankName: "HDFC Bank",
        pennyDropVerified: true,
      },
      approvalTrail: [
        { id: "T1", actorId: "staff-credit_executive", actorName: STAFF_PERSONAS.CREDIT_EXECUTIVE.name, role: "CREDIT_EXECUTIVE", action: "RECOMMEND", createdAt: daysAgo(3) },
        { id: "T2", actorId: "staff-credit_head", actorName: STAFF_PERSONAS.CREDIT_HEAD.name, role: "CREDIT_HEAD", action: "APPROVE", createdAt: daysAgo(2) },
        { id: "T3", actorId: "staff-disbursement_head", actorName: STAFF_PERSONAS.DISBURSEMENT_HEAD.name, role: "DISBURSEMENT_HEAD", action: "RELEASE", notes: "Released for transfer.", createdAt: daysAgo(0) },
      ],
    }),
    app({
      id: "APP-2030",
      applicantName: "Saanvi Joshi",
      employer: "Deloitte",
      designation: "Audit Associate",
      monthlySalary: 68000,
      salaryDay: 1,
      riskCategory: "A",
      creditScore: 770,
      stage: "ACTIVE",
      bank: {
        holderName: "Saanvi Joshi",
        accountMasked: "XXXX XXXX 5567",
        ifsc: "SBIN0001122",
        bankName: "State Bank of India",
        pennyDropVerified: true,
      },
      loan: makeLoanRecord("LN-7781", 17000, 1, 12),
    }),
  ];
}

export function seedCollections(): CollectionsCaseRecord[] {
  return [
    {
      id: "COL-901",
      loanId: "LN-7720",
      applicantId: "APP-1990",
      applicantName: "Rohan Das",
      mobile: "98201 11223",
      outstanding: 14300,
      daysPastDue: 5,
      bucket: "T0_T7",
      assignedOfficerId: "staff-collection_officer",
      assignedOfficerName: STAFF_PERSONAS.COLLECTION_EXECUTIVE.name,
      interactions: [
        { id: "I1", channel: "CALL", outcome: "PROMISE_TO_PAY", notes: "Will pay by Friday after salary credit.", officerName: STAFF_PERSONAS.COLLECTION_EXECUTIVE.name, at: daysAgo(1) },
      ],
      createdAt: daysAgo(5),
    },
    {
      id: "COL-902",
      loanId: "LN-7705",
      applicantId: "APP-1977",
      applicantName: "Meera Pillai",
      mobile: "99860 44556",
      outstanding: 9200,
      daysPastDue: 19,
      bucket: "T8_T30",
      assignedOfficerName: STAFF_PERSONAS.COLLECTION_EXECUTIVE.name,
      assignedOfficerId: "staff-collection_officer",
      interactions: [
        { id: "I1", channel: "WHATSAPP", outcome: "NO_RESPONSE", officerName: STAFF_PERSONAS.COLLECTION_EXECUTIVE.name, at: daysAgo(3) },
        { id: "I2", channel: "CALL", outcome: "DISPUTE", notes: "Disputes interest; escalated for review.", officerName: STAFF_PERSONAS.COLLECTION_EXECUTIVE.name, at: daysAgo(1) },
      ],
      createdAt: daysAgo(19),
    },
    {
      id: "COL-903",
      loanId: "LN-7680",
      applicantId: "APP-1955",
      applicantName: "Aditya Nanda",
      mobile: "90080 77889",
      outstanding: 22600,
      daysPastDue: 47,
      bucket: "T30_T60",
      interactions: [],
      settlement: { amount: 16000, status: "PROPOSED", proposedBy: STAFF_PERSONAS.COLLECTION_EXECUTIVE.name, at: daysAgo(2) },
      createdAt: daysAgo(47),
    },
    {
      id: "COL-904",
      loanId: "LN-7611",
      applicantId: "APP-1921",
      applicantName: "Tara Menon",
      mobile: "97410 33221",
      outstanding: 31100,
      daysPastDue: 96,
      bucket: "T90_PLUS",
      interactions: [],
      createdAt: daysAgo(96),
    },
  ];
}

export function seedStaff(): StaffMember[] {
  const roles = Object.keys(STAFF_PERSONAS) as Array<keyof typeof STAFF_PERSONAS>;
  return roles.map((role, i) => ({
    id: `staff-${role.toLowerCase()}`,
    name: STAFF_PERSONAS[role].name,
    email: STAFF_PERSONAS[role].email,
    role,
    active: true,
    lastActive: daysAgo(i % 4),
    createdAt: daysAgo(120 - i * 10),
  }));
}

export function seedInvites(): InviteRecord[] {
  return [
    { id: "INV-1", email: "neha.bansal@navix.finance", role: "CREDIT_EXECUTIVE", status: "PENDING", invitedByName: STAFF_PERSONAS.ADMIN.name, expiresAt: daysAgo(-5), createdAt: daysAgo(2) },
    { id: "INV-2", email: "karthik.r@navix.finance", role: "COLLECTION_EXECUTIVE", status: "ACCEPTED", invitedByName: STAFF_PERSONAS.ADMIN.name, expiresAt: daysAgo(-3), createdAt: daysAgo(8) },
    { id: "INV-3", email: "old.invite@navix.finance", role: "ACCOUNTANT", status: "EXPIRED", invitedByName: STAFF_PERSONAS.ADMIN.name, expiresAt: daysAgo(10), createdAt: daysAgo(40) },
  ];
}

export function seedBlocklist(): BlocklistEntry[] {
  return [
    { id: "BL-1", type: "PAN", value: "ABCDE1234F", reason: "Confirmed identity fraud (chargeback ring).", addedByName: STAFF_PERSONAS.ADMIN.name, createdAt: daysAgo(30) },
    { id: "BL-2", type: "MOBILE", value: "90000 00000", reason: "Multiple fake-KYC attempts.", addedByName: STAFF_PERSONAS.COLLECTION_HEAD.name, createdAt: daysAgo(14) },
    { id: "BL-3", type: "BANK", value: "XXXX 9981 (IFSC YESB0001)", reason: "Mule account flagged by partner NBFC.", addedByName: STAFF_PERSONAS.ADMIN.name, createdAt: daysAgo(7) },
  ];
}

export const RISK_CATEGORIES: RiskCategory[] = ["A", "B", "C", "D"];
