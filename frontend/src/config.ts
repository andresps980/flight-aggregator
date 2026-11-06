// Runtime configuration, written by the nginx container at start from its environment (docker/entrypoint script),
// so one image serves every environment (ADR-0024). Read once before the app renders.

export interface AppConfig {
  /** Show the demo controls panel that toggles gds-mock provider outages (spec 006 AC-22). */
  demoControls: boolean;
}

const DEFAULTS: AppConfig = { demoControls: false };

/** Missing, unreachable or malformed config means the defaults (demo controls off). */
export async function loadConfig(): Promise<AppConfig> {
  try {
    const response = await fetch(new URL('/config.json', window.location.href));
    if (!response.ok) return DEFAULTS;
    const body: unknown = await response.json();
    const demoControls = (body as { demoControls?: unknown } | null)?.demoControls;
    return { demoControls: demoControls === true };
  } catch {
    return DEFAULTS;
  }
}
