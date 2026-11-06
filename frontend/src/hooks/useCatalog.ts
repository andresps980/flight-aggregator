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
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  // Changing this number re-runs the effect below, i.e. fetches again.
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    getCatalog({ signal: controller.signal })
      .then((result) => {
        setCatalog(result);
        setError(null);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted) return;
        setError(toApiError(e));
        setLoading(false);
      });
    return () => controller.abort();
  }, [generation]);

  // `loading` is set here, in the event, rather than inside the effect (React's rule: effects should not set state
  // synchronously).
  const reload = useCallback(() => {
    setLoading(true);
    setGeneration((g) => g + 1);
  }, []);
  return { catalog, loading, error, reload };
}
