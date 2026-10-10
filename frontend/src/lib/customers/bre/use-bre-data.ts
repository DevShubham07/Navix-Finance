"use client";

/**
 * Loads everything the BRE tab and the CRIF analysis panel read, on the same React Query keys the
 * other Customer 360 tabs use so nothing is fetched twice.
 *
 * Reborrows split evidence across files: the new application's BUREAU row (and its raw report), PAN,
 * EMAIL and SALARY rows stay on the source application, and the typed Aadhaar is not copied onto the
 * new profile. So when the selected file lacks something, earlier files are consulted — newest first,
 * never a file newer than the one being viewed.
 */

import * as React from "react";
import { useQueries, useQuery, type UseQueryResult } from "@tanstack/react-query";
import {
  customersApi,
  staffApi,
  type CreditBriefView,
  type CustomerDetail,
  type DocumentView,
  type ProfileView,
  type StepResult,
} from "@/lib/api/applications";
import { bankAnalysisApi } from "@/lib/api/bank-analysis";
import { istCalendarToday } from "@/lib/customers/customer-360";
import { parseBureauReport, reportFromTradelines, type BureauReport } from "@/lib/customers/bre/bureau-report";
import { analyseProfile, type ProfileAnalysis } from "@/lib/customers/bre/profile-analysis";
import { evaluateBre, mergeSteps, type BreResult } from "@/lib/customers/bre/rules";

/** How many earlier applications to consult when the selected one lacks a check or a report. */
const MAX_EARLIER = 3;
/** Checks whose absence on the selected file sends us to earlier files. */
const CORE_CHECKS = ["PAN", "AADHAAR", "EMPLOYMENT", "EMAIL", "BUREAU"];

/** Module-level so React Query can memoise the combined result across renders. */
function combineData<T>(results: UseQueryResult<T>[]): { data: (T | undefined)[]; isLoading: boolean } {
  return { data: results.map((r) => r.data), isLoading: results.some((r) => r.isLoading) };
}

/** The selected application, then up to MAX_EARLIER older ones (newest first). */
export function applicationChain(detail: CustomerDetail, applicationId: number | null): number[] {
  if (applicationId == null) return [];
  const ids = detail.applications.map((a) => a.id);
  const at = ids.indexOf(applicationId);
  const older = (at >= 0 ? ids.slice(at + 1) : ids.filter((id) => id < applicationId)).filter((id) => id < applicationId);
  return [applicationId, ...older.slice(0, MAX_EARLIER)];
}

export interface BureauReportState {
  report: BureauReport | null;
  /** The brief the report came from (or the selected file's brief when there is no report). */
  brief: CreditBriefView | null;
  isLoading: boolean;
  error: unknown;
  refetch: () => void;
}

/** The bureau report for a file: its raw response, else an earlier file's, else its parsed tradelines. */
export function useBureauReport(detail: CustomerDetail, applicationId: number | null): BureauReportState {
  const chain = React.useMemo(() => applicationChain(detail, applicationId), [detail, applicationId]);
  const primary = useQuery({
    queryKey: ["credit-brief", applicationId],
    queryFn: () => staffApi.creditBrief(applicationId as number),
    enabled: applicationId != null,
    retry: false,
  });
  const primaryReport = React.useMemo(
    () => (primary.data ? parseBureauReport(primary.data.providerResponse, primary.data.applicationId) : null),
    [primary.data],
  );
  const needEarlier = primary.isSuccess && primaryReport == null && chain.length > 1;
  const earlier = useQueries({
    queries: chain.slice(1).map((id) => ({
      queryKey: ["credit-brief", id],
      queryFn: () => staffApi.creditBrief(id),
      enabled: needEarlier,
      retry: false,
    })),
    combine: combineData<CreditBriefView>,
  });
  const { refetch: refetchPrimary } = primary;
  const refetch = React.useCallback(() => void refetchPrimary(), [refetchPrimary]);
  return React.useMemo(() => {
    const briefs = [primary.data, ...earlier.data].filter((b): b is CreditBriefView => b != null);
    let report: BureauReport | null = null;
    let brief: CreditBriefView | null = primary.data ?? null;
    for (const b of briefs) {
      const r = parseBureauReport(b.providerResponse, b.applicationId);
      if (r) {
        report = r;
        brief = b;
        break;
      }
    }
    if (!report) {
      const withTradelines = briefs.find((b) => (b.facts?.detail?.tradelines?.length ?? 0) > 0);
      if (withTradelines) {
        report = reportFromTradelines(withTradelines);
        brief = withTradelines;
      }
    }
    return {
      report,
      brief,
      isLoading: primary.isLoading || (needEarlier && earlier.isLoading),
      error: primary.error,
      refetch,
    };
  }, [primary.data, primary.isLoading, primary.error, refetch, needEarlier, earlier]);
}

/** Verification rows for the file, topped up from earlier files for checks it lacks. */
export function useMergedSteps(detail: CustomerDetail, applicationId: number | null) {
  const chain = React.useMemo(() => applicationChain(detail, applicationId), [detail, applicationId]);
  const primary = useQuery({
    queryKey: ["verifications", applicationId],
    queryFn: () => staffApi.verifications(applicationId as number),
    enabled: applicationId != null,
    retry: false,
  });
  const missing = primary.isSuccess && CORE_CHECKS.some((c) => !(primary.data ?? []).some((s) => s.checkType === c));
  const earlier = useQueries({
    queries: chain.slice(1).map((id) => ({
      queryKey: ["verifications", id],
      queryFn: () => staffApi.verifications(id),
      enabled: missing,
      retry: false,
    })),
    combine: combineData<StepResult[]>,
  });
  const steps = React.useMemo<StepResult[]>(
    () => mergeSteps([primary.data, ...earlier.data]),
    [primary.data, earlier],
  );
  return {
    steps,
    isLoading: primary.isLoading || (missing && earlier.isLoading),
    error: primary.error,
    refetch: () => void primary.refetch(),
  };
}

/** The CRIF analysis for the Banking / Third-party tabs (no rules, just the reading of the report). */
export function useProfileAnalysis(detail: CustomerDetail, applicationId: number | null) {
  const bureau = useBureauReport(detail, applicationId);
  const stepsQ = useMergedSteps(detail, applicationId);
  const analysis = React.useMemo<ProfileAnalysis>(
    () => analyseProfile(bureau.report, detail.profile, stepsQ.steps, bureau.brief?.generatedAt, istCalendarToday()),
    [bureau.report, bureau.brief, detail.profile, stepsQ.steps],
  );
  return { ...bureau, analysis, isLoading: bureau.isLoading || stepsQ.isLoading };
}

export interface BreData {
  result: BreResult | null;
  report: BureauReport | null;
  brief: CreditBriefView | null;
  documents: { applicationId: number; doc: DocumentView }[];
  isLoading: boolean;
  error: unknown;
  refetch: () => void;
}

export function useBreData(detail: CustomerDetail, customerId: number, applicationId: number | null): BreData {
  const chain = React.useMemo(() => applicationChain(detail, applicationId), [detail, applicationId]);
  const bureau = useBureauReport(detail, applicationId);
  const stepsQ = useMergedSteps(detail, applicationId);
  const docsQ = useQuery({
    queryKey: ["customer-documents", customerId],
    queryFn: () => customersApi.documents(customerId),
    enabled: applicationId != null,
  });
  const bankQ = useQuery({ queryKey: ["bank-analysis", customerId], queryFn: () => bankAnalysisApi.get(customerId) });
  // A reborrow's newest profile has no typed Aadhaar — read it from an earlier file's own profile.
  const needAadhaar = applicationId != null && !detail.profile?.aadhaar && chain.length > 1;
  const profilesQ = useQueries({
    queries: chain.slice(1).map((id) => ({
      queryKey: ["staff-profile", id],
      queryFn: () => staffApi.getProfile(id),
      enabled: needAadhaar,
      retry: false,
    })),
    combine: combineData<ProfileView>,
  });
  const documents = React.useMemo(
    () =>
      (docsQ.data ?? [])
        .filter((g) => !chain.length || g.applicationId <= (applicationId ?? Infinity))
        .flatMap((g) => g.documents.map((doc) => ({ applicationId: g.applicationId, doc }))),
    [docsQ.data, chain.length, applicationId],
  );
  const aadhaar = detail.profile?.aadhaar ?? profilesQ.data.map((p) => p?.aadhaar).find((a) => !!a) ?? null;
  const result = React.useMemo<BreResult | null>(() => {
    if (applicationId == null) return null;
    const brief = bureau.brief;
    return evaluateBre({
      today: istCalendarToday(),
      profile: detail.profile,
      steps: stepsQ.steps,
      report: bureau.report,
      bureau: {
        state: brief?.bureauState ?? null,
        score: brief?.creditScore ?? null,
        source: brief?.bureauSource ?? detail.profile?.bureauSource ?? null,
        generatedAt: brief?.generatedAt ?? null,
      },
      aadhaar,
      documents,
      bankSalaryPaise: bankQ.data?.salary?.minSalaryPaise ?? null,
      bankAnalyserLive: bankQ.data != null,
      // Off unless the staging build sets it — production always evaluates every rule.
      stagingPass: process.env.NEXT_PUBLIC_BRE_STAGING_PASS === "true",
    });
  }, [applicationId, bureau.brief, bureau.report, detail.profile, stepsQ.steps, aadhaar, documents, bankQ.data]);
  return {
    result,
    report: bureau.report,
    brief: bureau.brief,
    documents,
    isLoading: bureau.isLoading || stepsQ.isLoading || docsQ.isLoading,
    // A failed bureau read only empties the bureau rules; the verification rows decide the tab.
    error: stepsQ.error,
    refetch: () => {
      bureau.refetch();
      stepsQ.refetch();
      void docsQ.refetch();
    },
  };
}
