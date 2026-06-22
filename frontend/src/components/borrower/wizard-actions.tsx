"use client";

import * as React from "react";
import Link from "next/link";
import { ArrowLeft, ArrowRight } from "lucide-react";
import { Button } from "@/components/ui";
import { cn } from "@/lib/utils";

/**
 * Standard footer for every wizard/flow step: a quiet Back control on the left
 * and a primary Continue on the right. Stacks full-width on mobile so the
 * primary action is always thumb-reachable.
 */
export interface WizardActionsProps {
  backHref?: string;
  onBack?: () => void;
  continueLabel?: string;
  continueHref?: string;
  onContinue?: () => void;
  loading?: boolean;
  disabled?: boolean;
  /** Render Continue as a form submit button. */
  submit?: boolean;
  className?: string;
}

export function WizardActions({
  backHref,
  onBack,
  continueLabel = "Continue",
  continueHref,
  onContinue,
  loading,
  disabled,
  submit,
  className,
}: WizardActionsProps) {
  const back =
    backHref || onBack ? (
      backHref ? (
        <Link href={backHref} className="btn btn-outline btn-sm order-2 sm:order-1">
          <ArrowLeft size={16} /> Back
        </Link>
      ) : (
        <button type="button" onClick={onBack} className="btn btn-outline btn-sm order-2 sm:order-1">
          <ArrowLeft size={16} /> Back
        </button>
      )
    ) : (
      <span className="hidden sm:block" />
    );

  const next = continueHref ? (
    <Link
      href={continueHref}
      className={cn("btn btn-gold order-1 sm:order-2 sm:w-auto", disabled && "pointer-events-none opacity-50")}
      aria-disabled={disabled}
    >
      {continueLabel} <ArrowRight size={16} />
    </Link>
  ) : (
    <Button
      type={submit ? "submit" : "button"}
      variant="gold"
      onClick={onContinue}
      isLoading={loading}
      disabled={disabled}
      rightIcon={<ArrowRight size={16} />}
      className="order-1 sm:order-2"
    >
      {continueLabel}
    </Button>
  );

  return (
    <div className={cn("mt-8 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between", className)}>
      {back}
      {next}
    </div>
  );
}
