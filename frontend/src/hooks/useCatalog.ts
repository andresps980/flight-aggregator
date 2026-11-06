// Loads the catalog (dropdown values) once, and again on demand (after "Reload results", spec 006 AC-1, AC-18).
import { useCallback, useEffect, useState } from 'react';
import { ApiError, getCatalog, toApiError } from '../api/client';
import type { Catalog } from '../api/types';

export interface CatalogState {
  catalog: Catalog | null;
  loading: boolean;
  error: ApiError | null;
  reload: () => void;
}

export function useCatalog(): CatalogState {
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [error, setError] = useState<ApiError | null>(null);
  // `requested` counts the loads asked for, `answered` the last one that came back (with data or an error), so
  // "loading" is derived instead of being set inside the effect.
  const [requested, setRequested] = useState(0);
  const [answered, setAnswered] = useState(-1);

  useEffect(() => {
    const controller = new AbortController();
    getCatalog({ signal: controller.signal })
      .then((result) => {
        setCatalog(result);
        setError(null);
        setAnswered(requested);
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted) return;
        setError(toApiError(e));
        setAnswered(requested);
      });
    return () => controller.abort();
  }, [requested]);

  const reload = useCallback(() => setRequested((n) => n + 1), []);
  return { catalog, loading: answered !== requested, error, reload };
}
