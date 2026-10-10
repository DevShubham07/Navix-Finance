"use client";

import * as React from "react";
import { Building2, ChevronRight, Users } from "lucide-react";
import { dashboardApi, paiseToINR } from "@/lib/api/applications";
import { ChartCard } from "../chart-card";
import { PctChip, ProgressBar } from "../chart-parts";
import { PCT_TONE_FILL, STAT_CHIP, TONE_SOLID, TONE_TEXT, fmtPct, pctTone } from "../colors";
import { nf } from "../fmt";
import type { DashTabProps } from "../tab-props";
import { useDashQuery } from "../use-dash-query";
import { RankBadge, StatChip } from "../ui-bits";

const th = "px-3 py-2 font-semibold";

/** Admin "Company-wise Performance": Users by Company + Performance by Company. */
export function CompaniesTab({ params, open }: DashTabProps) {
  const q = useDashQuery("companies", params, [], () => dashboardApi.companies(params));
  const d = q.data;
  const users = d?.users ?? [];
  const perf = d?.performance ?? [];
  const maxUsers = Math.max(1, ...users.map((u) => u.users));
  const maxDisbursed = Math.max(1, ...perf.map((p) => p.disbursedPaise));
  const openCompany = (company: string) => open({ metric: "COMPANY", key: company, title: `Company · ${company}` });
  const shared = { loading: q.isLoading, error: q.error, onRetry: () => void q.refetch() };

  return (
    <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
      <ChartCard
        title={
          <span className="flex items-center gap-2">
            <Users size={16} aria-hidden /> Users by Company
          </span>
        }
        info="Customers grouped by employer (latest profile). Share = customers of that company ÷ all customers. Click a row for the applications."
        accent={TONE_SOLID.blue}
        controls={
          d && (
            <>
              <StatChip {...STAT_CHIP.blue} label="Users" value={nf(d.totalUsers)} />
              <StatChip {...STAT_CHIP.violet} label="Companies" value={nf(d.totalCompanies)} />
            </>
          )
        }
        empty={users.length === 0}
        {...shared}
      >
        <div className="staff-table-scroll">
          <table className="w-full min-w-[30rem] text-left text-xs">
            <thead className="border-b border-line bg-grey-50 text-slate">
              <tr>
                <th className={th}>#</th>
                <th className={th}>Company</th>
                <th className={`${th} text-right`}>Users</th>
                <th className={th}>Share</th>
                <th className={`${th} text-right`}>Avg salary</th>
                <th className={th}>Type</th>
              </tr>
            </thead>
            <tbody>
              {users.map((u, i) => (
                <tr
                  key={u.company}
                  tabIndex={0}
                  onClick={() => openCompany(u.company)}
                  onKeyDown={(e) => e.key === "Enter" && openCompany(u.company)}
                  title={`${u.company}: ${nf(u.users)} users · ${u.sharePct == null ? "—" : `${u.sharePct.toFixed(1)}%`} of customers · avg salary ${paiseToINR(u.averageSalaryPaise)}`}
                  className="cursor-pointer border-b border-line hover:bg-grey-50"
                >
                  <td className="px-3 py-1.5">
                    <RankBadge rank={i + 1} />
                  </td>
                  <td className="px-3 py-1.5 font-semibold text-ink">{u.company}</td>
                  <td className="px-3 py-1.5 text-right font-semibold tabular-nums" style={{ color: TONE_TEXT.blue }}>{nf(u.users)}</td>
                  <td className="w-36 px-3 py-1.5">
                    <span className="flex items-center gap-2">
                      <ProgressBar ratio={u.users / maxUsers} color={TONE_SOLID.blue} className="w-20" />
                      <span className="tabular-nums text-muted">{u.sharePct == null ? "—" : `${u.sharePct.toFixed(1)}%`}</span>
                    </span>
                  </td>
                  <td className="px-3 py-1.5 text-right tabular-nums">{paiseToINR(u.averageSalaryPaise)}</td>
                  <td className="px-3 py-1.5 text-muted">{u.employmentType ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </ChartCard>

      <ChartCard
        title={
          <span className="flex items-center gap-2">
            <Building2 size={16} aria-hidden /> Performance by Company
          </span>
        }
        info="Loans disbursed in the period by the borrower's employer. Collection % = verified payments ÷ total repayable of those loans."
        accent={TONE_SOLID.emerald}
        empty={perf.length === 0}
        {...shared}
      >
        <div className="staff-table-scroll">
          <table className="w-full min-w-[34rem] text-left text-xs">
            <thead className="border-b border-line bg-grey-50 text-slate">
              <tr>
                <th className={th}>#</th>
                <th className={th}>Company</th>
                <th className={`${th} text-right`}>Disbursed</th>
                <th className={th}>Principal</th>
                <th className={`${th} text-right`}>Repayable</th>
                <th className={`${th} text-right`}>Collected</th>
                <th className={th}>Collection %</th>
                <th className={th} aria-label="Open" />
              </tr>
            </thead>
            <tbody>
              {perf.map((p, i) => {
                const tone = pctTone(p.collectionPct);
                return (
                  <tr
                    key={p.company}
                    tabIndex={0}
                    onClick={() => openCompany(p.company)}
                    onKeyDown={(e) => e.key === "Enter" && openCompany(p.company)}
                    title={`${p.company}: ${nf(p.disbursed)} loans · principal ${paiseToINR(p.disbursedPaise)} · repayable ${paiseToINR(p.repayablePaise)} · collected ${paiseToINR(p.collectedPaise)} (${fmtPct(p.collectionPct, 1)})`}
                    className="cursor-pointer border-b border-line hover:bg-grey-50"
                  >
                    <td className="px-3 py-1.5">
                      <RankBadge rank={i + 1} />
                    </td>
                    <td className="px-3 py-1.5 font-semibold text-ink">{p.company}</td>
                    <td className="px-3 py-1.5 text-right font-semibold tabular-nums" style={{ color: TONE_TEXT.blue }}>{nf(p.disbursed)}</td>
                    <td className="w-40 px-3 py-1.5">
                      <span className="block tabular-nums">{paiseToINR(p.disbursedPaise)}</span>
                      <ProgressBar ratio={p.disbursedPaise / maxDisbursed} color={TONE_SOLID.emerald} className="mt-0.5" />
                    </td>
                    <td className="px-3 py-1.5 text-right tabular-nums">{paiseToINR(p.repayablePaise)}</td>
                    <td className="px-3 py-1.5 text-right tabular-nums">{paiseToINR(p.collectedPaise)}</td>
                    <td className="w-32 px-3 py-1.5">
                      <span className="flex items-center gap-1.5">
                        <PctChip ratio={p.collectionPct} />
                        <ProgressBar ratio={p.collectionPct} color={PCT_TONE_FILL[tone]} className="w-12" />
                      </span>
                    </td>
                    <td className="px-1 text-muted">
                      <ChevronRight size={14} aria-hidden />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </ChartCard>
    </div>
  );
}
