import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * Button — emits the design system's `.btn` classes for pixel-fidelity with
 * the "Classic Corporate" theme (gold/navy/outline), plus semantic variants
 * (destructive/success/warning) composed on the same base.
 */
type ButtonVariant =
  | "primary" // navy
  | "gold"
  | "secondary" // outline
  | "outline"
  | "outline-light"
  | "ghost"
  | "destructive"
  | "success"
  | "warning"
  | "info";
type ButtonSize = "xs" | "sm" | "md" | "lg" | "xl";

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  block?: boolean;
  isLoading?: boolean;
  leftIcon?: React.ReactNode;
  rightIcon?: React.ReactNode;
}

const variantClasses: Record<ButtonVariant, string> = {
  primary: "btn-navy",
  gold: "btn-gold",
  secondary: "btn-outline",
  outline: "btn-outline",
  "outline-light": "btn-outline-light",
  ghost: "bg-transparent border-transparent text-navy hover:bg-navy-tint",
  destructive: "bg-error-600 border-error-600 text-white hover:bg-error-700 hover:border-error-700",
  success: "bg-success-600 border-success-600 text-white hover:bg-success-700 hover:border-success-700",
  warning: "bg-warning-600 border-warning-600 text-white hover:bg-warning-700 hover:border-warning-700",
  info: "btn-navy",
};

export const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      className,
      variant = "primary",
      size = "md",
      block = false,
      isLoading = false,
      disabled = false,
      leftIcon,
      rightIcon,
      children,
      ...props
    },
    ref,
  ) => {
    return (
      <button
        ref={ref}
        disabled={disabled || isLoading}
        className={cn(
          "btn",
          variantClasses[variant],
          (size === "sm" || size === "xs") && "btn-sm",
          block && "btn-block",
          "disabled:pointer-events-none disabled:opacity-50",
          className,
        )}
        {...props}
      >
        {isLoading && (
          <svg className="animate-spin" width={16} height={16} viewBox="0 0 24 24" fill="none">
            <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
            <path
              className="opacity-75"
              fill="currentColor"
              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
            />
          </svg>
        )}
        {!isLoading && leftIcon}
        {children}
        {!isLoading && rightIcon}
      </button>
    );
  },
);
Button.displayName = "Button";
