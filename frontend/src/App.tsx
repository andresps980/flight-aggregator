// The dashboard screen. It wires the hooks (state) to the panels (presentation):
//   URL filters → useSearch → results and charts;  useIngestionStatus (polling) → status panel and "new data" banner.
import { useCallback, useEffect, useState } from 'react';
import type { AppConfig } from './config';
import { DemoControls } from './components/DemoControls';
import { NewDataBanner } from './components/NewDataBanner';
import { PriceHistogram } from './components/PriceHistogram';
import { ProblemAlert } from './components/ProblemAlert';
import { ProviderChart } from './components/ProviderChart';
import { ResultsList } from './components/ResultsList';
import { SearchPanel } from './components/SearchPanel';
import { StatusPanel } from './components/StatusPanel';
import type { Filters } from './filters';
import { useCatalog } from './hooks/useCatalog';
import { useIngestionStatus } from './hooks/useIngestionStatus';
import { useSearch } from './hooks/useSearch';
import { useUrlFilters } from './hooks/useUrlFilters';
import { baselineOf, isNewDataAvailable, type Baseline } from './newData';

/** The search that runs at page load is compared with whatever the first status poll returns. */
const FIRST_POLL = 'first-poll';

export default function App({ config }: { config: AppConfig }) {
  const catalog = useCatalog();
  const search = useSearch();
  const status = useIngestionStatus();
  const { latest, loaded, initialLatest } = status;

  // The run the current results were loaded under (AC-18), recorded whenever a search starts.
  const [baseline, setBaseline] = useState<Baseline | typeof FIRST_POLL>(FIRST_POLL);

  const { search: runSearch } = search;
  const startSearch = useCallback(
    (next: Filters) => {
      setBaseline(loaded ? baselineOf(latest) : FIRST_POLL);
      runSearch(next);
    },
    [runSearch, loaded, latest],
  );

  // Back/forward restore the filters from the URL and rerun their search (AC-6).
  const { filters, navigation, pushFilters } = useUrlFilters(startSearch);

  // The search for the URL the page was opened with (AC-6), once. Its baseline is FIRST_POLL (initial state).
  useEffect(() => {
    runSearch(filters);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only at mount; later searches start from events
  }, []);

  const onSearch = (next: Filters) => startSearch(pushFilters(next));
  const reload = () => {
    catalog.reload();
    startSearch(filters);
  };

  const effectiveBaseline =
    baseline === FIRST_POLL
      ? initialLatest === undefined
        ? undefined
        : baselineOf(initialLatest)
      : baseline;
  const newData = effectiveBaseline !== undefined && isNewDataAvailable(effectiveBaseline, latest);

  return (
    <div className="app">
      <header className="app-header">
        <h1>Flight Aggregator</h1>
        <p className="muted">
          Cheapest itineraries across Mock-Alpha, Mock-Beta and Mock-Gamma · times in UTC
        </p>
      </header>
      {/* Grid areas (styles.css): side by side on wide screens; on narrow ones the search panel stays on top and the
          status and demo panels move below the results. */}
      <main className="layout">
        <section className="panel area-search" aria-labelledby="search-heading">
          <h2 id="search-heading">Search</h2>
          {catalog.error && <ProblemAlert error={catalog.error} />}
          <SearchPanel
            // A new key on back/forward remounts the form with the restored filters. Only then: remounting on
            // every search would drop the keyboard focus from the Search button.
            key={navigation}
            catalog={catalog.catalog}
            catalogLoading={catalog.loading && catalog.catalog === null}
            filters={filters}
            busy={search.busy}
            onSearch={onSearch}
          />
        </section>
        <div className="area-status">
          <StatusPanel status={status} />
        </div>
        {config.demoControls && (
          <div className="area-demo">
            <DemoControls />
          </div>
        )}
        <div className="content area-results">
          <NewDataBanner visible={newData} onReload={reload} />
          {search.items.length > 0 && (
            <div className="charts">
              <PriceHistogram items={search.items} />
              <ProviderChart items={search.items} />
            </div>
          )}
          <ResultsList search={search} />
        </div>
      </main>
    </div>
  );
}
