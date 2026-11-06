import { act, renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { IngestionRun } from '../api/types';
import { problem, run } from '../test/fixtures';
import { problemResponse, recordRequests, server } from '../test/server';
import { IDLE_POLL_MS, RUNNING_POLL_MS, useIngestionStatus } from './useIngestionStatus';

function serveLatest(...answers: (IngestionRun | 'error')[]) {
    let call = 0;
    server.use(
        http.get('/api/v1/ingestions/latest', () => {
            const answer = answers[Math.min(call, answers.length - 1)];
            call += 1;
            return answer === 'error'
                ? new HttpResponse('boom', { status: 500 })
                : HttpResponse.json(answer);
        }),
    );
}

function setVisibility(state: 'visible' | 'hidden') {
    Object.defineProperty(document, 'visibilityState', { value: state, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
}

describe('useIngestionStatus', () => {
    beforeEach(() => {
        vi.useFakeTimers({ shouldAdvanceTime: true });
    });
    afterEach(() => {
        setVisibility('visible');
        vi.useRealTimers();
    });

    it('AC-16: polls every 30 s while idle (3 s and 30 s per ADR-0023)', async () => {
        expect(IDLE_POLL_MS).toBe(30_000);
        expect(RUNNING_POLL_MS).toBe(3_000);
        const polls = recordRequests('/api/v1/ingestions/latest');
        const { result } = renderHook(() => useIngestionStatus());
        await waitFor(() => expect(result.current.latest?.id).toBe('run-1'));
        expect(polls).toHaveLength(1);

        await act(() => vi.advanceTimersByTimeAsync(29_000));
        expect(polls).toHaveLength(1);
        await act(() => vi.advanceTimersByTimeAsync(1_500));
        expect(polls).toHaveLength(2);
    });

    it('AC-16: polls every 3 s while the latest run is RUNNING, then back to 30 s', async () => {
        serveLatest(run({ status: 'RUNNING' }), run({ status: 'RUNNING' }), run());
        const polls = recordRequests('/api/v1/ingestions/latest');
        const { result } = renderHook(() => useIngestionStatus());
        await waitFor(() => expect(result.current.latest?.status).toBe('RUNNING'));

        await act(() => vi.advanceTimersByTimeAsync(3_100));
        expect(polls).toHaveLength(2);
        await act(() => vi.advanceTimersByTimeAsync(3_100));
        expect(polls).toHaveLength(3);
        await waitFor(() => expect(result.current.latest?.status).toBe('COMPLETED'));

        await act(() => vi.advanceTimersByTimeAsync(10_000));
        expect(polls).toHaveLength(3);
    });

    it('AC-16: pauses while the tab is hidden and polls at once when it becomes visible', async () => {
        const polls = recordRequests('/api/v1/ingestions/latest');
        const { result } = renderHook(() => useIngestionStatus());
        await waitFor(() => expect(result.current.loaded).toBe(true));

        act(() => setVisibility('hidden'));
        await act(() => vi.advanceTimersByTimeAsync(120_000));
        expect(polls).toHaveLength(1);

        act(() => setVisibility('visible'));
        await waitFor(() => expect(polls).toHaveLength(2));
    });

    it('AC-16: a failed poll marks the status unavailable and polling continues', async () => {
        serveLatest('error', run());
        const { result } = renderHook(() => useIngestionStatus());
        await waitFor(() => expect(result.current.unavailable).toBe(true));

        await act(() => vi.advanceTimersByTimeAsync(IDLE_POLL_MS + 500));

        await waitFor(() => expect(result.current.unavailable).toBe(false));
        expect(result.current.latest?.id).toBe('run-1');
    });

    it('AC-15: no run yet (404) is a loaded state with no latest run, not an error', async () => {
        server.use(
            http.get('/api/v1/ingestions/latest', () =>
                problemResponse(problem(404, 'Ingestion not found', 'No ingestion has run yet')),
            ),
        );
        const { result } = renderHook(() => useIngestionStatus());

        await waitFor(() => expect(result.current.loaded).toBe(true));
        expect(result.current.latest).toBeNull();
        expect(result.current.unavailable).toBe(false);
    });

    it('AC-17: refresh on 202 shows the new run as RUNNING at once and switches to fast polling', async () => {
        server.use(
            http.post('/api/v1/ingestions', () =>
                HttpResponse.json(run({ id: 'new', status: 'RUNNING', trigger: 'MANUAL' }), {
                    status: 202,
                }),
            ),
        );
        const polls = recordRequests('/api/v1/ingestions/latest');
        const { result } = renderHook(() => useIngestionStatus());
        await waitFor(() => expect(result.current.loaded).toBe(true));
        serveLatest(run({ id: 'new', status: 'RUNNING' }));

        await act(() => result.current.refresh());

        expect(result.current.latest).toMatchObject({ id: 'new', status: 'RUNNING' });
        expect(result.current.refreshOutcome).toEqual({ kind: 'started', runId: 'new' });
        await act(() => vi.advanceTimersByTimeAsync(3_100));
        expect(polls).toHaveLength(2);
    });

    it('AC-17: refresh on 409 reports the running run and switches to fast polling', async () => {
        server.use(
            http.post('/api/v1/ingestions', () =>
                problemResponse(
                    problem(409, 'Ingestion already running', 'Ingestion r9 is in progress', {
                        runningIngestionId: 'r9',
                    }),
                ),
            ),
        );
        const polls = recordRequests('/api/v1/ingestions/latest');
        const { result } = renderHook(() => useIngestionStatus());
        await waitFor(() => expect(result.current.loaded).toBe(true));

        await act(() => result.current.refresh());

        expect(result.current.refreshOutcome).toEqual({
            kind: 'conflict',
            runningIngestionId: 'r9',
        });
        await act(() => vi.advanceTimersByTimeAsync(3_100));
        expect(polls).toHaveLength(2);
    });

    it('AC-17: any other refresh error is reported', async () => {
        server.use(http.post('/api/v1/ingestions', () => new HttpResponse(null, { status: 503 })));
        const { result } = renderHook(() => useIngestionStatus());
        await waitFor(() => expect(result.current.loaded).toBe(true));

        await act(() => result.current.refresh());

        expect(result.current.refreshOutcome).toMatchObject({ kind: 'error' });
        expect(result.current.refreshing).toBe(false);
    });

    it('NFR: polling stops when the component unmounts', async () => {
        const polls = recordRequests('/api/v1/ingestions/latest');
        const { result, unmount } = renderHook(() => useIngestionStatus());
        await waitFor(() => expect(result.current.loaded).toBe(true));

        unmount();
        await act(() => vi.advanceTimersByTimeAsync(120_000));

        expect(polls).toHaveLength(1);
    });
});
