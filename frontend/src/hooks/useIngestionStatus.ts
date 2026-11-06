// The latest ingestion run, kept fresh by polling (ADR-0023), and the "Refresh data" action (spec 006 AC-15 – AC-17).
import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError, getLatestIngestion, startIngestion, toApiError } from '../api/client';
import type { IngestionRun } from '../api/types';

/** Poll interval while no run is in progress. */
export const IDLE_POLL_MS = 30_000;
/** Poll interval while a run is RUNNING (a run takes ~15–20 s at 100k itineraries). */
export const RUNNING_POLL_MS = 3_000;

export type RefreshOutcome =
  | { kind: 'started'; runId: string }
  | { kind: 'conflict'; runningIngestionId: string | null }
  | { kind: 'error'; error: ApiError };

export interface IngestionStatus {
  /** The latest run from the last successful poll; null if none has run yet (404). */
  latest: IngestionRun | null;
  /** The first poll has answered (with a run or with 404). */
  loaded: boolean;
  /** What the first successful poll returned: the run current when the page loaded (undefined until then). */
  initialLatest: IngestionRun | null | undefined;
  /** The last poll failed; the previous `latest` is kept and polling continues. */
  unavailable: boolean;
  refreshing: boolean;
  refreshOutcome: RefreshOutcome | null;
  refresh: () => Promise<void>;
}

export function useIngestionStatus(): IngestionStatus {
  const [latest, setLatest] = useState<IngestionRun | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [initialLatest, setInitialLatest] = useState<IngestionRun | null | undefined>(undefined);
  const [unavailable, setUnavailable] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshOutcome, setRefreshOutcome] = useState<RefreshOutcome | null>(null);

  // The polling loop lives in the effect below; refresh() uses this to make it poll again soon.
  const pollSoonRef = useRef<() => void>(() => {});
  const latestRef = useRef<IngestionRun | null>(null);

  const showRun = useCallback((run: IngestionRun | null) => {
    latestRef.current = run;
    setLatest(run);
  }, []);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let controller: AbortController | undefined;
    const hidden = () => document.visibilityState === 'hidden';

    const schedule = (ms: number) => {
      clearTimeout(timer);
      timer = setTimeout(() => void poll(), ms);
    };

    const poll = async () => {
      if (hidden()) return; // paused; the visibility listener polls again when the tab is shown
      // Only one poll at a time: a newer poll (or refresh) aborts the older one, whose answer is then ignored.
      controller?.abort();
      const mine = new AbortController();
      controller = mine;
      try {
        const run = await getLatestIngestion({ signal: mine.signal });
        if (cancelled || mine.signal.aborted) return;
        showRun(run);
        setInitialLatest((first) => (first === undefined ? run : first));
        setUnavailable(false);
        setLoaded(true);
      } catch {
        if (cancelled || mine.signal.aborted) return;
        setUnavailable(true);
      }
      schedule(latestRef.current?.status === 'RUNNING' ? RUNNING_POLL_MS : IDLE_POLL_MS);
    };

    const onVisibilityChange = () => {
      clearTimeout(timer);
      if (!hidden()) void poll();
    };

    // After "Refresh data": drop a poll still in flight (it may return the run before the one just started, and
    // would reschedule at the idle pace), then poll again in 3 s.
    pollSoonRef.current = () => {
      controller?.abort();
      schedule(RUNNING_POLL_MS);
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    void poll();
    return () => {
      cancelled = true;
      clearTimeout(timer);
      controller?.abort();
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [showRun]);

  const refresh = useCallback(async () => {
    setRefreshing(true);
    try {
      const outcome = await startIngestion();
      if (outcome.kind === 'started') {
        showRun(outcome.run); // shown as RUNNING at once, before the next poll
        setRefreshOutcome({ kind: 'started', runId: outcome.run.id });
      } else {
        setRefreshOutcome(outcome);
      }
      pollSoonRef.current();
    } catch (error) {
      setRefreshOutcome({ kind: 'error', error: toApiError(error) });
    } finally {
      setRefreshing(false);
    }
  }, [showRun]);

  return { latest, loaded, initialLatest, unavailable, refreshing, refreshOutcome, refresh };
}
