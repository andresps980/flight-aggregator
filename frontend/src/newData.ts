// When to offer "New data available" (spec 006 AC-18, Q5). Pure functions, so the rule is tested on its own.
import type { IngestionRun } from './api/types';

/** The latest run as seen when the current search started; null if no run existed. */
export type Baseline = { runId: string; finished: boolean } | null;

export function baselineOf(run: IngestionRun | null): Baseline {
  return run ? { runId: run.id, finished: run.status !== 'RUNNING' } : null;
}

/**
 * True when the latest run published data (COMPLETED or PARTIAL) that the current results may not include: it is a
 * different run from the baseline, or the baseline run was still running when the search started. FAILED and
 * ABORTED runs never count (the status panel warns about them instead).
 */
export function isNewDataAvailable(baseline: Baseline, latest: IngestionRun | null): boolean {
  if (latest === null || (latest.status !== 'COMPLETED' && latest.status !== 'PARTIAL'))
    return false;
  if (baseline === null) return true;
  return latest.id !== baseline.runId || !baseline.finished;
}
