import * as React from "react";
import { cn } from "@/lib/utils";

/**
 * Input — renders the design system `.field` structure (label, input, hint,
 * error) so it inherits the global form styling. `size` is omitted from the
 * native attrs to expose our own size scale without a type clash.
 */
type InputSize = "sm" | "md" | "lg";

export interface InputProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, "size"> {
  size?: InputSize;
  label?: string;
  required?: boolean;
  error?: string;
  helperText?: string;
  leftIcon?: React.ReactNode;
  rightIcon?: React.ReactNode;
  /** Wrapper className (the `.field`); use `inputClassName` for the input. */
  className?: string;
  inputClassName?: string;
}

export const Input = React.forwardRef<HTMLInputElement, InputProps>(
  (
    {
      className,
      inputClassName,
      type = "text",
      size = "md",
      label,
      required,
      error,
      helperText,
      leftIcon,
      rightIcon,
      id,
      ...props
    },
    ref,
  ) => {
    const autoId = React.useId();
    const inputId = id ?? autoId;
    const sizeClass = size === "sm" ? "text-sm" : size === "lg" ? "text-lg" : undefined;
    return (
      <div className={cn("field", error && "invalid", className)}>
        {label && (
          <label htmlFor={inputId}>
            {label}
            {required ? <span className="req"> *</span> : null}
          </label>
        )}
        <div style={leftIcon || rightIcon ? { position: "relative" } : undefined}>
          {leftIcon && (
            <span style={{ position: "absolute", left: 12, top: "50%", transform: "translateY(-50%)", color: "var(--muted)" }}>
              {leftIcon}
            </span>
          )}
          <input
            ref={ref}
            id={inputId}
            type={type}
            className={cn(sizeClass, leftIcon && "pl-10", rightIcon && "pr-10", inputClassName)}
            aria-invalid={!!error}
            {...props}
          />
          {rightIcon && (
            <span style={{ position: "absolute", right: 12, top: "50%", transform: "translateY(-50%)", color: "var(--muted)" }}>
              {rightIcon}
            </span>
          )}
        </div>
        {error ? <p className="err" style={{ display: "block" }}>{error}</p> : helperText ? <p className="hint">{helperText}</p> : null}
      </div>
    );
  },
);
Input.displayName = "Input";
