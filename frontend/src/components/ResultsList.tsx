// The results (AC-7, AC-10 – AC-14): the loaded itineraries, how many are loaded, and keyset paging controls.
import type { Search } from '../hooks/useSearch';
import { plural } from '../format';
import { ItineraryRow } from './ItineraryRow';
import { ProblemAlert } from './ProblemAlert';

export function ResultsList({ search }: { search: Search }) {
  const { items, phase, hasMore, error, busy } = search;
  const finished = phase === 'done';
  return (
    <section className="results" aria-label="Results">
      {/* Announced to screen readers whenever the count changes (AC-21). No total: keyset paging has none. */}
      <p className="results-count" aria-live="polite">
        {phase === 'loading'
          ? 'Searching…'
          : items.length > 0 && plural(items.length, 'itinerary loaded', 'itineraries loaded')}
      </p>

      {error && (
        <ProblemAlert error={error}>
          <button type="button" onClick={search.restart}>
            Restart search
          </button>
        </ProblemAlert>
      )}

      {finished && items.length === 0 && !hasMore && (
        <p className="empty">No itineraries match these filters</p>
      )}

      {items.length > 0 && (
        <ol className="itineraries" aria-label="Itineraries">
          {items.map((itinerary) => (
            <ItineraryRow key={itinerary.itineraryKey} itinerary={itinerary} />
          ))}
        </ol>
      )}

      <div className="paging">
        {hasMore && !error && (
          <button
            type="button"
            onClick={search.loadMore}
            disabled={busy}
            aria-busy={phase === 'loadingMore'}
          >
            Load more
          </button>
        )}
        {finished && !hasMore && items.length > 0 && <p className="muted">End of results</p>}
      </div>
    </section>
  );
}
