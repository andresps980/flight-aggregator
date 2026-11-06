import { act, screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { itinerary, page, problem, providerReport, run } from './test/fixtures';
import { catalogLoaded, renderApp, resultRows } from './test/render';
import { problemResponse, recordRequests, server } from './test/server';

function statusPanel() {
    return screen.getByRole('region', { name: 'Data status' });
}

describe('status panel', () => {
    it('AC-15: shows the latest run: status, trigger, times, duration and stored itineraries', async () => {
        renderApp();

        const panel = await screen.findByRole('region', { name: 'Data status' });
        await waitFor(() => expect(panel).toHaveTextContent('COMPLETED'));
        expect(panel).toHaveTextContent('Scheduled');
        expect(panel).toHaveTextContent('06 Nov 10:00 UTC');
        expect(panel).toHaveTextContent('17.5 s');
        expect(panel).toHaveTextContent('94,543 itineraries stored');
    });

    it('AC-15: shows each provider with partitions, breaker state (OPEN highlighted, in words) and failures', async () => {
        const latest = run({ status: 'PARTIAL' });
        latest.report!.providers = [
            providerReport(),
            providerReport({
                provider: 'mock-beta',
                status: 'FAILED',
                partitionsSucceeded: 0,
                partitionsFailed: 7,
                circuitState: 'OPEN',
                failures: Array.from({ length: 7 }, (_, i) => ({
                    day: `2026-11-0${i + 1}`,
                    kind: 'CIRCUIT_OPEN' as const,
                    attempts: 0,
                    detail: 'breaker open',
                })),
            }),
        ];
        server.use(http.get('/api/v1/ingestions/latest', () => HttpResponse.json(latest)));
        renderApp();

        const beta = await screen.findByRole('row', { name: /mock-beta/ });
        expect(beta).toHaveTextContent('FAILED');
        expect(beta).toHaveTextContent('0/7');
        expect(beta).toHaveTextContent('7 failures');
        expect(within(beta).getByText('OPEN')).toHaveClass('breaker-open');
        const alpha = screen.getByRole('row', { name: /mock-alpha/ });
        expect(alpha).toHaveTextContent('7/7');
        expect(within(alpha).getByText('CLOSED')).not.toHaveClass('breaker-open');
    });

    it('AC-15: per-day outcomes are collapsed by default', async () => {
        const latest = run();
        latest.report!.days[0]!.outcome = 'KEPT_STALE';
        latest.report!.days[0]!.missing = ['mock-beta'];
        server.use(http.get('/api/v1/ingestions/latest', () => HttpResponse.json(latest)));
        const { user } = renderApp();

        const days = await screen.findByText('Days');
        expect(days.closest('details')).not.toHaveAttribute('open');
        await user.click(days);
        expect(statusPanel()).toHaveTextContent('2026-11-08');
        expect(statusPanel()).toHaveTextContent('KEPT_STALE');
        expect(statusPanel()).toHaveTextContent('missing mock-beta');
    });

    it('AC-15: a running run shows "running for" instead of a finish time', async () => {
        server.use(
            http.get('/api/v1/ingestions/latest', () =>
                HttpResponse.json(run({ status: 'RUNNING' })),
            ),
        );
        renderApp();

        await waitFor(() => expect(statusPanel()).toHaveTextContent('RUNNING'));
        expect(statusPanel()).toHaveTextContent(/running for/i);
    });

    it('AC-15: with no run yet (404) it says so and that the first scheduled run starts shortly', async () => {
        server.use(
            http.get('/api/v1/ingestions/latest', () =>
                problemResponse(problem(404, 'Ingestion not found', 'No ingestion has run yet')),
            ),
        );
        renderApp();

        await waitFor(() => expect(statusPanel()).toHaveTextContent('No ingestion has run yet'));
        expect(statusPanel()).toHaveTextContent(
            /first scheduled run starts shortly after startup/i,
        );
    });

    it.each(['FAILED', 'ABORTED'] as const)(
        'AC-15 (Q5): a %s latest run shows a warning that data may be stale, with its failure message',
        async (status) => {
            server.use(
                http.get('/api/v1/ingestions/latest', () =>
                    HttpResponse.json(
                        run({ status, failure: 'Unexpected error (IllegalStateException)' }),
                    ),
                ),
            );
            renderApp();

            await waitFor(() => expect(statusPanel()).toHaveTextContent(/data may be stale/i));
            expect(statusPanel()).toHaveTextContent('Unexpected error (IllegalStateException)');
        },
    );

    it('AC-16: a failed poll shows a non-blocking "status unavailable" note', async () => {
        server.use(
            http.get('/api/v1/ingestions/latest', () => new HttpResponse(null, { status: 502 })),
        );
        renderApp();

        await waitFor(() => expect(statusPanel()).toHaveTextContent(/status unavailable/i));
        expect(screen.getByRole('button', { name: 'Search' })).toBeEnabled();
    });
});

describe('refresh data', () => {
    it('AC-17: on 202 the panel shows the started run as RUNNING and the button is disabled', async () => {
        server.use(
            http.post('/api/v1/ingestions', () =>
                HttpResponse.json(run({ id: 'r42', status: 'RUNNING', trigger: 'MANUAL' }), {
                    status: 202,
                }),
            ),
        );
        const { user } = renderApp();
        const button = await screen.findByRole('button', { name: 'Refresh data' });
        await waitFor(() => expect(button).toBeEnabled());
        server.use(
            http.get('/api/v1/ingestions/latest', () =>
                HttpResponse.json(run({ id: 'r42', status: 'RUNNING' })),
            ),
        );

        await user.click(button);

        expect(await screen.findByText('Ingestion started (run r42)')).toBeInTheDocument();
        expect(statusPanel()).toHaveTextContent('RUNNING');
        expect(button).toBeDisabled();
    });

    it('AC-17: on 409 it says a run is already in progress, with its id', async () => {
        server.use(
            http.post('/api/v1/ingestions', () =>
                problemResponse(
                    problem(409, 'Ingestion already running', 'Ingestion r9 is in progress', {
                        runningIngestionId: 'r9',
                    }),
                ),
            ),
        );
        const { user } = renderApp();
        const button = await screen.findByRole('button', { name: 'Refresh data' });
        await waitFor(() => expect(button).toBeEnabled());

        await user.click(button);

        expect(
            await screen.findByText('An ingestion is already running (run r9)'),
        ).toBeInTheDocument();
    });

    it('AC-17: other errors are shown readably', async () => {
        server.use(
            http.post('/api/v1/ingestions', () =>
                problemResponse(problem(500, 'Internal Server Error', 'Database unavailable')),
            ),
        );
        const { user } = renderApp();
        const button = await screen.findByRole('button', { name: 'Refresh data' });
        await waitFor(() => expect(button).toBeEnabled());

        await user.click(button);

        expect(
            await screen.findByText(/Internal Server Error — Database unavailable/),
        ).toBeInTheDocument();
    });

    it('AC-17: the button is disabled while its request is in flight and while the latest run is RUNNING', async () => {
        server.use(
            http.post('/api/v1/ingestions', async () => {
                await delay(100);
                return HttpResponse.json(run({ id: 'r1', status: 'RUNNING' }), { status: 202 });
            }),
        );
        const { user } = renderApp();
        const button = await screen.findByRole('button', { name: 'Refresh data' });
        await waitFor(() => expect(button).toBeEnabled());

        await user.click(button);
        expect(button).toBeDisabled();
    });
});

describe('new data available', () => {
    afterEach(() => {
        vi.useRealTimers();
    });

    it('AC-18: when a newer run publishes data, a banner offers to reload; results are not replaced', async () => {
        let latest = run({ id: 'a' });
        server.use(
            http.get('/api/v1/ingestions/latest', () => HttpResponse.json(latest)),
            http.get('/api/v1/itineraries', () => HttpResponse.json(page([itinerary()]))),
            http.post('/api/v1/ingestions', () => {
                latest = run({ id: 'b', status: 'RUNNING' });
                return HttpResponse.json(latest, { status: 202 });
            }),
        );
        const searches = recordRequests('/api/v1/itineraries');
        const catalogs = recordRequests('/api/v1/catalog');
        vi.useFakeTimers({ shouldAdvanceTime: true });
        const { user } = renderApp();
        await catalogLoaded();
        await waitFor(() => expect(resultRows()).toHaveLength(1));

        // The user starts a run; it completes while polling every 3 s.
        await user.click(screen.getByRole('button', { name: 'Refresh data' }));
        latest = run({ id: 'b' });
        await act(() => vi.advanceTimersByTimeAsync(3_100));

        expect(await screen.findByText('New data available')).toBeInTheDocument();
        expect(searches).toHaveLength(1);

        await user.click(screen.getByRole('button', { name: 'Reload results' }));

        await waitFor(() => expect(searches).toHaveLength(2));
        expect(catalogs).toHaveLength(2);
        await waitFor(() =>
            expect(screen.queryByText('New data available')).not.toBeInTheDocument(),
        );
    });

    it('AC-18: a FAILED run never shows the banner (the panel warns instead)', async () => {
        let latest = run({ id: 'a' });
        server.use(
            http.get('/api/v1/ingestions/latest', () => HttpResponse.json(latest)),
            http.post('/api/v1/ingestions', () => {
                latest = run({ id: 'b', status: 'RUNNING' });
                return HttpResponse.json(latest, { status: 202 });
            }),
        );
        vi.useFakeTimers({ shouldAdvanceTime: true });
        const { user } = renderApp();
        await catalogLoaded();
        await waitFor(() =>
            expect(screen.getByRole('button', { name: 'Refresh data' })).toBeEnabled(),
        );

        await user.click(screen.getByRole('button', { name: 'Refresh data' }));
        latest = run({ id: 'b', status: 'FAILED', failure: 'every provider failed' });
        await act(() => vi.advanceTimersByTimeAsync(3_100));

        await waitFor(() => expect(statusPanel()).toHaveTextContent(/data may be stale/i));
        expect(screen.queryByText('New data available')).not.toBeInTheDocument();
    });
});
