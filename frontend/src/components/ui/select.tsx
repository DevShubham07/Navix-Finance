import * as React from "react";
import { cn } from "@/lib/utils";

/** Select — renders the design `.field` structure with a native `<select>`. */
export interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  label?: string;
  required?: boolean;
  error?: string;
  helperText?: string;
  options?: Array<{ value: string | number; label: string }>;
  /** Wrapper className (the `.field`). */
  className?: string;
}

export const Select = React.forwardRef<HTMLSelectElement, SelectProps>(
  ({ className, label, required, error, helperText, options, children, id, ...props }, ref) => {
    const autoId = React.useId();
    const selectId = id ?? autoId;
    return (
      <div className={cn("field", error && "invalid", className)}>
        {label && (
          <label htmlFor={selectId}>
            {label}
            {required ? <span className="req"> *</span> : null}
          </label>
        )}
        <select ref={ref} id={selectId} aria-invalid={!!error} {...props}>
          {options
            ? options.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))
            : children}
        </select>
        {error ? <p className="err" style={{ display: "block" }}>{error}</p> : helperText ? <p className="hint">{helperText}</p> : null}
      </div>
    );
  },
);
Select.displayName = "Select";
