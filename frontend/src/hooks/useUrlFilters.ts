// Keeps the search filters in the page URL (spec 006 AC-6): read at load, pushed on submit, restored on back/forward.
import { useCallback, useEffect, useState } from 'react';
import { fromSearchParams, toSearchParams, type Filters } from '../filters';

const currentFilters = () => fromSearchParams(new URLSearchParams(window.location.search));

export interface UrlFilters {
  filters: Filters;
  /** Counts back/forward navigations; the form uses it as its React `key` to show the restored filters. */
  navigation: number;
  /** Writes the filters to the URL (a new history entry unless unchanged) and returns them normalised. */
  pushFilters: (filters: Filters) => Filters;
}

/** `onNavigate` is called with the filters the browser restored on back/forward. */
export function useUrlFilters(onNavigate: (filters: Filters) => void): UrlFilters {
  const [filters, setFilters] = useState<Filters>(currentFilters);
  const [navigation, setNavigation] = useState(0);

  useEffect(() => {
    const onPopState = () => {
      const restored = currentFilters();
      setFilters(restored);
      setNavigation((n) => n + 1);
      onNavigate(restored);
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [onNavigate]);

  const pushFilters = useCallback((next: Filters) => {
    const query = toSearchParams(next).toString();
    if (query !== new URLSearchParams(window.location.search).toString()) {
      window.history.pushState(null, '', `${window.location.pathname}${query ? `?${query}` : ''}`);
    }
    const normalised = fromSearchParams(toSearchParams(next));
    setFilters(normalised);
    return normalised;
  }, []);

  return { filters, navigation, pushFilters };
}
