"use client";

import * as React from "react";
import { Search, X } from "lucide-react";
import { Input } from "@/components/ui";

/**
 * The staff console's list search: type freely, search on Enter or the Search button.
 *
 * Typing only edits a local draft — nothing reaches the page (and so nothing reaches a React Query
 * key) until the search is submitted, so a list page fires exactly one request per search instead of
 * one per keystroke. Clearing the box commits `""`, which restores the unfiltered list.
 */
export function SearchBar({
  initialValue = "",
  onSearch,
  placeholder,
  ariaLabel,
  inputClassName = "w-80",
}: {
  initialValue?: string;
  onSearch: (term: string) => void;
  placeholder?: string;
  ariaLabel?: string;
  inputClassName?: string;
}) {
  const [draft, setDraft] = React.useState(initialValue);

  const clear = () => {
    setDraft("");
    onSearch("");
  };

  return (
    <form
      role="search"
      className="flex items-center gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        onSearch(draft.trim());
      }}
    >
      <Input
        aria-label={ariaLabel ?? placeholder ?? "Search"}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        placeholder={placeholder}
        leftIcon={<Search size={15} />}
        rightIcon={
          draft ? (
            <button type="button" onClick={clear} aria-label="Clear search" className="flex text-muted hover:text-ink">
              <X size={14} />
            </button>
          ) : undefined
        }
        className="!mb-0"
        inputClassName={inputClassName}
      />
      <button type="submit" className="btn btn-sm btn-navy">
        <Search size={13} /> Search
      </button>
    </form>
  );
}
