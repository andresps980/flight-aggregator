// Demo only (AC-22): toggles gds-mock provider outages through its admin API, to show resilience live. Rendered
// only when the runtime flag DEMO_CONTROLS is on; acts on the mock, never on a real provider.
import { useEffect, useState } from 'react';
import { ApiError, getMockProviders, setMockOutage, toApiError } from '../api/client';
import type { MockProvider } from '../api/types';
import { ProblemAlert } from './ProblemAlert';

export function DemoControls() {
  const [providers, setProviders] = useState<MockProvider[]>([]);
  const [error, setError] = useState<ApiError | null>(null);
  const [pending, setPending] = useState<string | null>(null);

  // Bumped to fetch the providers again after a toggle.
  const [generation, setGeneration] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    getMockProviders({ signal: controller.signal })
      .then((result) => {
        setProviders(result);
        setError(null);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) setError(toApiError(e));
      });
    return () => controller.abort();
  }, [generation]);

  const toggle = async (provider: MockProvider) => {
    setPending(provider.id);
    try {
      await setMockOutage(provider.id, !provider.outage);
      setGeneration((g) => g + 1);
    } catch (e) {
      setError(toApiError(e));
    } finally {
      setPending(null);
    }
  };

  return (
    <section className="panel demo-panel" aria-labelledby="demo-heading">
      <h2 id="demo-heading">Demo controls — act on gds-mock, not on real providers</h2>
      <p className="muted">
        Force a provider outage, then press <em>Refresh data</em>: the provider fails, its breaker
        opens, its days are kept stale and the results show <em>STALE</em> badges after reloading.
      </p>
      {error && <ProblemAlert error={error} />}
      <ul className="switches">
        {providers.map((provider) => (
          <li key={provider.id}>
            <label>
              <input
                type="checkbox"
                role="switch"
                checked={provider.outage}
                disabled={pending === provider.id}
                onChange={() => void toggle(provider)}
              />{' '}
              Outage {provider.id}
            </label>
          </li>
        ))}
      </ul>
    </section>
  );
}
