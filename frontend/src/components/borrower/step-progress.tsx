import * as React from "react";
import { cn } from "@/lib/utils";

// TODO: render the borrower onboarding wizard steps (KYC -> income -> offer -> agreement).
export interface StepProgressProps {
  steps: string[];
  /** Zero-based index of the active step. */
  currentStep: number;
  className?: string;
}

export function StepProgress({ steps, currentStep, className }: StepProgressProps) {
  return (
    <ol className={cn("flex items-center gap-2", className)}>
      {steps.map((label, index) => (
        <li key={label} className="flex items-center gap-2">
          <span
            className={cn(
              "flex h-7 w-7 items-center justify-center rounded-full text-xs font-medium",
              index <= currentStep ? "bg-blue-600 text-white" : "bg-gray-200 text-gray-500",
            )}
          >
            {index + 1}
          </span>
          <span className="text-sm">{label}</span>
        </li>
      ))}
    </ol>
  );
}
