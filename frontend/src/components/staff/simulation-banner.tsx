/** Always-visible strip for a simulation session. Deliberately has no dismiss control. */
export function SimulationBanner() {
  return (
    <div
      role="status"
      className="pointer-events-none fixed inset-x-0 bottom-0 z-50 bg-error-500 px-4 py-1.5 text-center text-xs font-bold tracking-wide text-white"
    >
      SIMULATION — training data only. Figures shown here are not real company performance.
    </div>
  );
}
