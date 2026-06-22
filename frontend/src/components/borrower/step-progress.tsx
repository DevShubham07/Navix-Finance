import * as React from "react";
import { Check } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * Wizard stepper matching the design's `.stepper`. Steps before the active one
 * render as "done" (green check); the active one is navy; the rest are muted.
 */
export interface StepProgressProps {
  steps: string[];
  /** Zero-based index of the active step. */
  currentStep: number;
  className?: string;
}

export function StepProgress({ steps, currentStep, className }: StepProgressProps) {
  return (
    <div className={cn("stepper", className)}>
      {steps.map((label, index) => {
        const done = index < currentStep;
        const active = index === currentStep;
        return (
          <React.Fragment key={label}>
            <div className={cn("s-item", done && "done", active && "active")}>
              <span className="s-dot">{done ? <Check size={16} strokeWidth={3} /> : index + 1}</span>
              <span className="s-label">{label}</span>
            </div>
            {index < steps.length - 1 ? <span className="s-line" /> : null}
          </React.Fragment>
        );
      })}
    </div>
  );
}
