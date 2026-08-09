# Staff Desktop Density Design

## Goal

Make the authenticated DhanBoost staff console approximately 20% more compact on laptop and desktop screens so staff tables fit within the available width more often and remain readable as single table rows. Preserve the current mobile sizing and horizontal scrolling behavior.

## Scope

This change applies only to `/staff` routes. It covers the staff shell, navigation, page headings, cards, form controls, action buttons, and every semantic table rendered inside the staff console. Borrower and marketing pages are unchanged.

The target baseline is a 1366px-wide laptop viewport. Desktop density begins at the existing Tailwind `lg` breakpoint (1024px). Below that breakpoint, current sizing and touch targets remain unchanged.

## Design

### Staff density boundary

The shared `StaffShell` will establish a route-scoped density marker while it is mounted. At desktop widths, that marker will reduce the document rem scale to 80%, allowing existing Tailwind typography, spacing, widths, and gaps to shrink consistently without editing every staff page. The marker will be removed when leaving the staff console so no borrower or marketing route inherits the compact scale.

Global pixel-based components that do not follow the rem scale, particularly `.btn`, `.btn-sm`, and staff table cells, will receive staff-scoped desktop overrides with approximately 20% smaller padding and gaps.

### Tables

All tables inside `.navix-crm` remain real semantic `<table>` elements with one record per row and headings at the top. At desktop widths:

- the generic 720px table minimum and the live-pipeline 1120px minimum are overridden to `min-width: 100%`;
- header and cell padding is reduced to a compact shared value;
- table font and header tracking are reduced proportionally;
- headings and ordinary cell content may wrap when necessary instead of forcing horizontal overflow;
- long identifiers may break safely rather than widening the entire table;
- the live-pipeline identity and action columns retain their sticky behavior.

Horizontal scrolling remains available as a fallback for genuinely wide datasets and remains the default on mobile. The design does not hide columns or data.

### Shell and controls

The staff sidebar, navigation spacing, top bar, page content padding, headings, metric cards, form fields, badges, and Tailwind-based gaps inherit the 80% desktop rem scale. Shared action buttons receive explicit compact desktop padding because their base padding is expressed in pixels. Icon-only controls keep their existing accessible labels and remain usable.

## Accessibility and responsive behavior

- Mobile and tablet layouts below 1024px are unchanged.
- No column or action is removed.
- Keyboard focus styles and semantic table structure remain intact.
- Compact desktop text remains proportional to the existing design; browser zoom still works normally.
- Overflow containers remain in place as a safety fallback rather than clipping content.

## Implementation boundaries

The primary changes should remain in the shared staff shell and global staff-scoped design-system rules. Individual staff pages should only be edited if a local fixed width defeats the shared rule. CSS `zoom` and transform scaling are excluded because they can disturb sticky positioning, modal geometry, and viewport calculations.

## Verification

Automated regression coverage will verify that the staff density marker is mounted and cleaned up and that the desktop density/table rules are present while mobile rules remain unaffected. Verification will include:

- the focused frontend regression test, run red then green;
- the full frontend Vitest suite;
- TypeScript compilation with `npx tsc --noEmit`;
- ESLint;
- a production frontend build where the repository's documented Next.js environment permits it;
- code-review-graph change detection for blast radius and test coverage;
- a final diff review confirming unrelated working-tree changes were not included.

## Success criteria

At 1366px desktop width, common staff registers and the live application queue use the full available content width with approximately 20% smaller platform sizing and materially less avoidable horizontal scrolling. Every record remains a table row, every heading and action remains available, and mobile screens retain their current readable layout.
