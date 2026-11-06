// The search results and keyset pagination (spec 006 AC-11 – AC-14, spec 005 "Paging rule for clients").
// A reducer keeps the state transitions in one place; `run` fetches pages and dispatches what happened.
import { useCallback, useReducer, useRef } from 'react';
import { ApiError, searchItineraries, toApiError } from '../api/client';
import type { Itinerary, ItineraryPage } from '../api/types';
import type { Filters } from '../filters';

/** Items per page (Q2: the API default). */
export const PAGE_SIZE = 20;
/** Pages that add no item fetched in a row before stopping and showing "Load more" again (AC-13). */
export const MAX_EMPTY_PAGES = 5;

export type SearchPhase = 'idle' | 'loading' | 'loadingMore' | 'done' | 'error';

export interface SearchState {
  items: Itinerary[];
  nextCursor: string | null;
  hasMore: boolean;
  phase: SearchPhase;
  error: ApiError | null;
}

type Action =
  | { type: 'start'; append: boolean }
  | { type: 'page'; page: ItineraryPage }
  | { type: 'done' }
  | { type: 'error'; error: ApiError };

const initialState: SearchState = {
  items: [],
  nextCursor: null,
  hasMore: false,
  phase: 'idle',
  error: null,
};

function reducer(state: SearchState, action: Action): SearchState {
  switch (action.type) {
    case 'start':
      // A new search clears the list; "load more" keeps it.
      return action.append
        ? { ...state, phase: 'loadingMore', error: null }
        : { ...initialState, phase: 'loading' };
    case 'page':
      return {
        ...state,
        items: [...state.items, ...action.page.items],
        nextCursor: action.page.nextCursor,
        hasMore: action.page.hasMore,
      };
    case 'done':
      return { ...state, phase: 'done' };
    case 'error':
      return { ...state, phase: 'error', error: action.error };
  }
}

export interface Search extends SearchState {
  /** True while a page request is in flight. */
  busy: boolean;
  /** Starts a new search for these filters (first page); supersedes any search in flight. */
  search: (filters: Filters) => void;
  /** Appends the next page(s) of the current search. */
  loadMore: () => void;
  /** Reruns the current search from the first page (after a failed page, AC-14). */
  restart: () => void;
}

export function useSearch(): Search {
  const [state, dispatch] = useReducer(reducer, initialState);
  const filtersRef = useRef<Filters>({});
  const controllerRef = useRef<AbortController | null>(null);

  const run = useCallback(async (filters: Filters, cursor: string | null, append: boolean) => {
    // Abort the previous request: its answer must never be displayed (AC-11).
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    filtersRef.current = filters;
    dispatch({ type: 'start', append });

    let next = cursor;
    let emptyPages = 0;
    try {
      // A page may be short or even empty while hasMore is true (a cached page drops departed flights), so an
      // empty page is not the end: keep fetching, but not forever (AC-13).
      for (;;) {
        const page = await searchItineraries(
          filters,
          { limit: PAGE_SIZE, cursor: next },
          { signal: controller.signal },
        );
        dispatch({ type: 'page', page });
        next = page.nextCursor;
        if (page.items.length > 0 || !page.hasMore) break;
        emptyPages += 1;
        if (emptyPages >= MAX_EMPTY_PAGES) break;
      }
      dispatch({ type: 'done' });
    } catch (error) {
      if (!controller.signal.aborted) dispatch({ type: 'error', error: toApiError(error) });
    }
  }, []);

  const search = useCallback((filters: Filters) => void run(filters, null, false), [run]);
  const restart = useCallback(() => void run(filtersRef.current, null, false), [run]);
  const { nextCursor, hasMore, phase } = state;
  const loadMore = useCallback(() => {
    if (phase === 'done' && hasMore) void run(filtersRef.current, nextCursor, true);
  }, [run, phase, hasMore, nextCursor]);

  const busy = phase === 'loading' || phase === 'loadingMore';
  return { ...state, busy, search, loadMore, restart };
}
