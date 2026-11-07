// The data/ingestion status (AC-15 – AC-17): the latest run, each provider's result and breaker, the days, and
// the "Refresh data" button. Data comes from useIngestionStatus (polling) via props.
import type { IngestionRun, ProviderReport } from '../api/types';
import { formatUtc, plural } from '../format';
import type { IngestionStatus, RefreshOutcome } from '../hooks/useIngestionStatus';

function elapsed(fromIso: string): string {
  const seconds = Math.max(0, Math.round((Date.now() - new Date(fromIso).getTime()) / 1000));
  return seconds < 60 ? `${seconds} s` : `${Math.floor(seconds / 60)} min ${seconds % 60} s`;
}

function outcomeText(outcome: RefreshOutcome | null): string {
  if (outcome === null) return '';
  switch (outcome.kind) {
    case 'started':
      return `Ingestion started (run ${outcome.runId})`;
    case 'conflict':
      return outcome.runningIngestionId
        ? `An ingestion is already running (run ${outcome.runningIngestionId})`
        : 'An ingestion is already running';
    case 'error':
      return `Could not start an ingestion: ${outcome.error.message}`;
  }
}

function ProviderRow({ provider }: { provider: ProviderReport }) {
  const open = provider.circuitState === 'OPEN';
  return (
    <tr>
      <th scope="row">{provider.provider}</th>
      <td>{provider.status}</td>
      <td>
        {provider.partitionsSucceeded}/{provider.partitionsRequested}
      </td>
      <td>
        <span className={open ? 'breaker breaker-open' : 'breaker'}>{provider.circuitState}</span>
      </td>
      <td className="count">{provider.failures.length}</td>
    </tr>
  );
}

function RunDetails({ run }: { run: IngestionRun }) {
  const running = run.status === 'RUNNING';
  const report = run.report;
  return (
    <>
      <dl className="run-facts">
        <dt>Status</dt>
        <dd>
          <span className={`run-status run-status-${run.status.toLowerCase()}`}>{run.status}</span>
        </dd>
        <dt>Trigger</dt>
        <dd>{run.trigger === 'MANUAL' ? 'Manual' : 'Scheduled'}</dd>
        <dt>Started</dt>
        <dd>{formatUtc(run.startedAt)} UTC</dd>
        {running || run.finishedAt === null ? (
          <>
            <dt>Progress</dt>
            <dd>running for {elapsed(run.startedAt)}</dd>
          </>
        ) : (
          <>
            <dt>Finished</dt>
            <dd>
              {formatUtc(run.finishedAt)} UTC
              {run.durationMs !== null && ` (${(run.durationMs / 1000).toFixed(1)} s)`}
            </dd>
          </>
        )}
        {report && (
          <>
            <dt>Data</dt>
            <dd>{plural(report.storedItineraries, 'itinerary stored', 'itineraries stored')}</dd>
          </>
        )}
      </dl>

      {(run.status === 'FAILED' || run.status === 'ABORTED') && (
        <p className="warning">
          <span aria-hidden="true">⚠ </span>
          Latest run {run.status === 'FAILED' ? 'failed' : 'was aborted'} — the data may be stale.
          {run.failure && ` ${run.failure}`}
        </p>
      )}

      {report && (
        <>
          <div className="table-scroll">
            <table className="providers">
              <caption className="visually-hidden">Providers in the latest run</caption>
              <thead>
                <tr>
                  <th scope="col">Provider</th>
                  <th scope="col">Result</th>
                  <th scope="col">Partitions</th>
                  <th scope="col">Breaker</th>
                  <th scope="col" className="count">
                    Failures
                  </th>
                </tr>
              </thead>
              <tbody>
                {report.providers.map((provider) => (
                  <ProviderRow key={provider.provider} provider={provider} />
                ))}
              </tbody>
            </table>
          </div>
          <details>
            <summary>Days</summary>
            <ul className="days">
              {report.days.map((day) => (
                <li key={day.day}>
                  {day.day} {day.outcome}
                  {day.missing.length > 0 && ` · missing ${day.missing.join(', ')}`}
                </li>
              ))}
            </ul>
          </details>
        </>
      )}
    </>
  );
}

export function StatusPanel({ status }: { status: IngestionStatus }) {
  const { latest, loaded, unavailable, refreshing, refreshOutcome, refresh } = status;
  const running = latest?.status === 'RUNNING';
  return (
    <section className="panel status-panel" aria-labelledby="status-heading">
      <h2 id="status-heading">Data status</h2>
      {unavailable && (
        <p className="note" role="status">
          Status unavailable — retrying.
        </p>
      )}
      {!loaded && !unavailable && <p className="muted">Loading status…</p>}
      {loaded && latest === null && (
        <p>No ingestion has run yet. The first scheduled run starts shortly after startup.</p>
      )}
      {latest && <RunDetails run={latest} />}
      <button type="button" onClick={() => void refresh()} disabled={refreshing || running}>
        Refresh data
      </button>
      <p className="refresh-status" data-testid="refresh-status" aria-live="polite">
        {outcomeText(refreshOutcome)}
      </p>
    </section>
  );
}
