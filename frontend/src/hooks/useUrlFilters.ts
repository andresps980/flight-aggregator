// Keeps the search filters in the page URL (spec 006 AC-6): read at load, pushed on submit, restored on back/forward.
import { useCallback, useEffect, useState } from 'react';
import { fromSearchParams, toSearchParams, type Filters } from '../filters';

const currentFilters = () => fromSearchParams(new URLSearchParams(window.location.search));

/**
 * Returns the current filters and a function that pushes new ones to the URL (returning them normalised).
 * `onNavigate` is called with the filters the browser restored on back/forward.
 */
export function useUrlFilters(
  onNavigate: (filters: Filters) => void,
): [Filters, (filters: Filters) => Filters] {
  const [filters, setFilters] = useState<Filters>(currentFilters);

  useEffect(() => {
    const onPopState = () => {
      const restored = currentFilters();
      setFilters(restored);
      onNavigate(restored);
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [onNavigate]);

  /** Writes the filters to the URL as a new history entry and makes them current. */
  const pushFilters = useCallback((next: Filters) => {
    const query = toSearchParams(next).toString();
    window.history.pushState(null, '', `${window.location.pathname}${query ? `?${query}` : ''}`);
    const normalised = fromSearchParams(toSearchParams(next));
    setFilters(normalised);
    return normalised;
  }, []);

  return [filters, pushFilters];
}
