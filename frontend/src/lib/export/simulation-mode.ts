/** Set by the staff shell from the session, so every export path is covered in one place. */
let simulationExports = false;

export function setExportSimulation(on: boolean): void {
  simulationExports = on;
}

export function isExportSimulation(): boolean {
  return simulationExports;
}
