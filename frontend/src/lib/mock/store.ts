"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";
import { buildCostBreakdown, dueDateFromSalary } from "@/lib/calc/loan-math";
import type { StaffRole, ApprovalTrailEntry } from "@/lib/domain/staff";
import {
  seedApplications,
  seedBlocklist,
  seedCollections,
  seedInvites,
  seedStaff,
} from "@/lib/mock/seed";
import type {
  ApplicationRecord,
  BlocklistEntry,
  CollectionsCaseRecord,
  InviteRecord,
  StaffMember,
} from "@/lib/mock/types";

// Unique across reloads. The store is `persist`ed to localStorage, so previously-generated ids
// rehydrate on load — but a plain module counter resets to its seed and would regenerate the same
// id (e.g. TRAIL-1001), producing duplicate React keys. Namespacing each page load with a session
// token keeps every generated id unique even after a reload.
let idCounter = 0;
const idSession = Date.now().toString(36);
const nextId = (prefix: string) => `${prefix}-${idSession}-${(idCounter += 1)}`;
const nowIso = () => new Date().toISOString();

interface Actor {
  id: string;
  name: string;
  role: StaffRole;
}

interface MockState {
  applications: ApplicationRecord[];
  collections: CollectionsCaseRecord[];
  staff: StaffMember[];
  invites: InviteRecord[];
  blocklist: BlocklistEntry[];

  // ---- selectors are derived in components; here are the mutators ----

  /** KYC Approver clears or rejects identity → moves to credit queue. */
  decideKyc: (appId: string, pass: boolean, actor: Actor, notes?: string) => void;
  /** Credit Head assigns an application to a Credit Executive. */
  assignExecutive: (appId: string, executive: StaffMember, actor: Actor) => void;
  /** Credit Executive records a recommendation → goes to Credit Head. */
  recommend: (appId: string, decision: "APPROVE" | "REJECT", notes: string, actor: Actor) => void;
  /** Credit Head's final decision. Approve → disbursement; reject → closed. */
  decideCredit: (appId: string, approve: boolean, notes: string, actor: Actor, approvedAmount?: number) => void;
  /** Disbursement Head releases funds → accounting confirms transfer. */
  releaseDisbursement: (appId: string, actor: Actor, notes?: string) => void;
  /** Accountant confirms the bank transfer → loan becomes active (or fails). */
  confirmTransfer: (appId: string, success: boolean, actor: Actor, notes?: string) => void;

  // collections
  assignOfficer: (caseId: string, officer: StaffMember) => void;
  logInteraction: (caseId: string, entry: CollectionsCaseRecord["interactions"][number]) => void;
  proposeSettlement: (caseId: string, amount: number, proposedByName: string) => void;
  decideSettlement: (caseId: string, approve: boolean) => void;

  // admin
  addStaff: (member: Omit<StaffMember, "id" | "createdAt">) => void;
  toggleStaffActive: (id: string) => void;
  sendInvite: (email: string, role: StaffRole, invitedByName: string) => void;
  revokeInvite: (id: string) => void;
  addBlocklist: (entry: Omit<BlocklistEntry, "id" | "createdAt">) => void;
  removeBlocklist: (id: string) => void;

  resetDemo: () => void;
}

function trail(entry: Omit<ApprovalTrailEntry, "id" | "createdAt">): ApprovalTrailEntry {
  return { ...entry, id: nextId("TRAIL"), createdAt: nowIso() };
}

export const useMockDb = create<MockState>()(
  persist(
    (set) => ({
      applications: seedApplications(),
      collections: seedCollections(),
      staff: seedStaff(),
      invites: seedInvites(),
      blocklist: seedBlocklist(),

      decideKyc: (appId, pass, actor, notes) =>
        set((s) => ({
          applications: s.applications.map((a) =>
            a.id === appId
              ? {
                  ...a,
                  stage: pass ? "CREDIT_QUEUE" : "REJECTED",
                  kyc: { ...a.kyc, overall: pass ? "PASSED" : "FAILED" },
                  updatedAt: nowIso(),
                  approvalTrail: [
                    ...a.approvalTrail,
                    trail({ actorId: actor.id, actorName: actor.name, role: actor.role, action: pass ? "APPROVE" : "REJECT", notes: notes ?? (pass ? "Identity verified." : "Identity check failed.") }),
                  ],
                }
              : a,
          ),
        })),

      assignExecutive: (appId, executive, actor) =>
        set((s) => ({
          applications: s.applications.map((a) =>
            a.id === appId
              ? {
                  ...a,
                  stage: "CREDIT_REVIEW",
                  assignedExecutiveId: executive.id,
                  assignedExecutiveName: executive.name,
                  updatedAt: nowIso(),
                  approvalTrail: [
                    ...a.approvalTrail,
                    trail({ actorId: actor.id, actorName: actor.name, role: actor.role, action: "RECOMMEND", notes: `Assigned to ${executive.name} for review.` }),
                  ],
                }
              : a,
          ),
        })),

      recommend: (appId, decision, notes, actor) =>
        set((s) => ({
          applications: s.applications.map((a) =>
            a.id === appId
              ? {
                  ...a,
                  stage: "CREDIT_DECISION",
                  recommendation: { decision, notes, by: actor.name, at: nowIso() },
                  updatedAt: nowIso(),
                  approvalTrail: [
                    ...a.approvalTrail,
                    trail({ actorId: actor.id, actorName: actor.name, role: actor.role, action: "RECOMMEND", notes }),
                  ],
                }
              : a,
          ),
        })),

      decideCredit: (appId, approve, notes, actor, approvedAmount) =>
        set((s) => ({
          applications: s.applications.map((a) =>
            a.id === appId
              ? {
                  ...a,
                  stage: approve ? "DISBURSEMENT" : "REJECTED",
                  requestedAmount: approve && approvedAmount ? approvedAmount : a.requestedAmount,
                  updatedAt: nowIso(),
                  approvalTrail: [
                    ...a.approvalTrail,
                    trail({ actorId: actor.id, actorName: actor.name, role: actor.role, action: approve ? "APPROVE" : "REJECT", notes }),
                  ],
                }
              : a,
          ),
        })),

      releaseDisbursement: (appId, actor, notes) =>
        set((s) => ({
          applications: s.applications.map((a) =>
            a.id === appId
              ? {
                  ...a,
                  stage: "ACCOUNTING",
                  updatedAt: nowIso(),
                  approvalTrail: [
                    ...a.approvalTrail,
                    trail({ actorId: actor.id, actorName: actor.name, role: actor.role, action: "RELEASE", notes: notes ?? "Funds released for bank transfer." }),
                  ],
                }
              : a,
          ),
        })),

      confirmTransfer: (appId, success, actor, notes) =>
        set((s) => ({
          applications: s.applications.map((a) => {
            if (a.id !== appId) return a;
            const disbursedOn = new Date();
            const due = dueDateFromSalary({ disbursedOn, salaryDay: a.salaryDay });
            const tenureDays = Math.max(1, Math.round((due.getTime() - disbursedOn.getTime()) / 864e5));
            const breakdown = buildCostBreakdown(a.requestedAmount, tenureDays);
            return {
              ...a,
              stage: success ? "ACTIVE" : "DISBURSEMENT",
              updatedAt: nowIso(),
              loan: success
                ? {
                    id: nextId("LN"),
                    principal: a.requestedAmount,
                    costBreakdown: breakdown,
                    dueDate: due.toISOString(),
                    disbursedAt: disbursedOn.toISOString(),
                    status: "ACTIVE",
                    repayments: [],
                    outstanding: breakdown.totalRepayable,
                  }
                : a.loan,
              approvalTrail: [
                ...a.approvalTrail,
                trail({ actorId: actor.id, actorName: actor.name, role: actor.role, action: "DISBURSE", notes: notes ?? (success ? "Bank transfer confirmed — loan active." : "Bank transfer failed — returned to disbursement.") }),
              ],
            };
          }),
        })),

      assignOfficer: (caseId, officer) =>
        set((s) => ({
          collections: s.collections.map((c) =>
            c.id === caseId ? { ...c, assignedOfficerId: officer.id, assignedOfficerName: officer.name } : c,
          ),
        })),

      logInteraction: (caseId, entry) =>
        set((s) => ({
          collections: s.collections.map((c) =>
            c.id === caseId ? { ...c, interactions: [entry, ...c.interactions] } : c,
          ),
        })),

      proposeSettlement: (caseId, amount, proposedByName) =>
        set((s) => ({
          collections: s.collections.map((c) =>
            c.id === caseId ? { ...c, settlement: { amount, status: "PROPOSED", proposedBy: proposedByName, at: nowIso() } } : c,
          ),
        })),

      decideSettlement: (caseId, approve) =>
        set((s) => ({
          collections: s.collections.map((c) =>
            c.id === caseId && c.settlement
              ? { ...c, settlement: { ...c.settlement, status: approve ? "APPROVED" : "REJECTED" } }
              : c,
          ),
        })),

      addStaff: (member) =>
        set((s) => ({ staff: [{ ...member, id: nextId("staff"), createdAt: nowIso() }, ...s.staff] })),

      toggleStaffActive: (id) =>
        set((s) => ({ staff: s.staff.map((m) => (m.id === id ? { ...m, active: !m.active } : m)) })),

      sendInvite: (email, role, invitedByName) =>
        set((s) => ({
          invites: [
            { id: nextId("INV"), email, role, status: "PENDING", invitedByName, expiresAt: new Date(Date.now() + 7 * 864e5).toISOString(), createdAt: nowIso() },
            ...s.invites,
          ],
        })),

      revokeInvite: (id) =>
        set((s) => ({ invites: s.invites.map((i) => (i.id === id ? { ...i, status: "REVOKED" } : i)) })),

      addBlocklist: (entry) =>
        set((s) => ({ blocklist: [{ ...entry, id: nextId("BL"), createdAt: nowIso() }, ...s.blocklist] })),

      removeBlocklist: (id) => set((s) => ({ blocklist: s.blocklist.filter((b) => b.id !== id) })),

      resetDemo: () =>
        set({
          applications: seedApplications(),
          collections: seedCollections(),
          staff: seedStaff(),
          invites: seedInvites(),
          blocklist: seedBlocklist(),
        }),
    }),
    { name: "navix-mock-db", version: 1 },
  ),
);
