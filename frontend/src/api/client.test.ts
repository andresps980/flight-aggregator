import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { catalog, itinerary, page, problem, run } from '../test/fixtures';
import { problemResponse, recordRequests, server } from '../test/server';
import {
    ApiError,
    getCatalog,
    getLatestIngestion,
    getMockProviders,
    searchItineraries,
    setMockOutage,
    startIngestion,
} from './client';

describe('API client', () => {
    it('AC-1: getCatalog returns the catalog', async () => {
        expect(await getCatalog()).toEqual(catalog);
    });

    it('AC-5: searchItineraries sends only the set filters, the limit and the cursor', async () => {
        const urls = recordRequests('/api/v1/itineraries');
        server.use(
            http.get('/api/v1/itineraries', () => HttpResponse.json(page([itinerary()], 'c2'))),
        );

        const result = await searchItineraries(
            { origin: 'mad', maxPrice: '650' },
            { limit: 20, cursor: 'c1' },
        );

        expect(result.hasMore).toBe(true);
        expect(urls[0]?.search).toBe('?origin=MAD&maxPrice=650&limit=20&cursor=c1');
    });

    it('AC-15: getLatestIngestion returns the run, or null when no run exists yet (404)', async () => {
        expect((await getLatestIngestion())?.id).toBe('run-1');

        server.use(
            http.get('/api/v1/ingestions/latest', () =>
                problemResponse(problem(404, 'Ingestion not found', 'No ingestion has run yet')),
            ),
        );
        expect(await getLatestIngestion()).toBeNull();
    });

    it('AC-17: startIngestion reports a started run on 202', async () => {
        server.use(
            http.post('/api/v1/ingestions', () =>
                HttpResponse.json(run({ id: 'new', status: 'RUNNING', trigger: 'MANUAL' }), {
                    status: 202,
                }),
            ),
        );

        const outcome = await startIngestion();

        expect(outcome).toMatchObject({ kind: 'started', run: { id: 'new', status: 'RUNNING' } });
    });

    it('AC-17: startIngestion reports a conflict on 409, with the running id when present', async () => {
        server.use(
            http.post('/api/v1/ingestions', () =>
                problemResponse(
                    problem(409, 'Ingestion already running', 'Ingestion r9 is in progress', {
                        runningIngestionId: 'r9',
                    }),
                ),
            ),
        );
        expect(await startIngestion()).toEqual({ kind: 'conflict', runningIngestionId: 'r9' });

        server.use(
            http.post('/api/v1/ingestions', () =>
                problemResponse(
                    problem(409, 'Ingestion already running', 'An ingestion is in progress'),
                ),
            ),
        );
        expect(await startIngestion()).toEqual({ kind: 'conflict', runningIngestionId: null });
    });

    it('AC-20: a Problem Details error carries its title and detail', async () => {
        server.use(
            http.get('/api/v1/itineraries', () =>
                problemResponse(
                    problem(400, 'Invalid request', 'maxPrice must have at most 2 decimals'),
                ),
            ),
        );

        const error = await searchItineraries({ maxPrice: '1' }, { limit: 20 }).catch(
            (e: unknown) => e,
        );

        expect(error).toBeInstanceOf(ApiError);
        expect(error).toMatchObject({
            status: 400,
            problem: { title: 'Invalid request', detail: 'maxPrice must have at most 2 decimals' },
            message: 'Invalid request — maxPrice must have at most 2 decimals',
        });
    });

    it.each([
        [
            'a 502 from nginx',
            () => new HttpResponse('<html>Bad Gateway</html>', { status: 502 }),
            502,
            /HTTP 502/,
        ],
        [
            'invalid JSON on 200',
            () => new HttpResponse('{oops', { headers: { 'Content-Type': 'application/json' } }),
            200,
            /invalid response/i,
        ],
        ['a network failure', () => HttpResponse.error(), 0, /could not reach the server/i],
    ])('AC-20: %s becomes a readable generic error', async (_, resolver, status, message) => {
        server.use(http.get('/api/v1/catalog', resolver));

        const error = await getCatalog().catch((e: unknown) => e);

        expect(error).toBeInstanceOf(ApiError);
        expect(error).toMatchObject({ status, problem: null });
        expect((error as ApiError).message).toMatch(message);
    });

    it('NFR: a request that takes longer than its timeout fails with a timeout error', async () => {
        server.use(
            http.get('/api/v1/catalog', async () => {
                await delay('infinite');
                return HttpResponse.json(catalog);
            }),
        );

        const error = await getCatalog({ timeoutMs: 20 }).catch((e: unknown) => e);

        expect(error).toBeInstanceOf(ApiError);
        expect((error as ApiError).message).toMatch(/timed out/i);
    });

    it('NFR: a request aborted by its caller rejects with an AbortError, not an ApiError', async () => {
        server.use(
            http.get('/api/v1/catalog', async () => {
                await delay('infinite');
                return HttpResponse.json(catalog);
            }),
        );
        const controller = new AbortController();

        const pending = getCatalog({ signal: controller.signal }).catch((e: unknown) => e);
        controller.abort();
        const error = await pending;

        expect(error).not.toBeInstanceOf(ApiError);
        expect((error as Error).name).toBe('AbortError');
    });

    it('AC-22: gds-mock admin calls go through the /gds-admin proxy', async () => {
        const calls: string[] = [];
        server.use(
            http.get('/gds-admin/providers', () =>
                HttpResponse.json([
                    {
                        id: 'mock-beta',
                        latencyMinMs: 100,
                        latencyMaxMs: 5000,
                        errorRate: 0.1,
                        outage: false,
                    },
                ]),
            ),
            http.post('/gds-admin/providers/:id/outage', ({ params }) => {
                calls.push(`POST ${String(params.id)}`);
                return new HttpResponse(null, { status: 204 });
            }),
            http.delete('/gds-admin/providers/:id/outage', ({ params }) => {
                calls.push(`DELETE ${String(params.id)}`);
                return new HttpResponse(null, { status: 204 });
            }),
        );

        expect((await getMockProviders())[0]?.id).toBe('mock-beta');
        await setMockOutage('mock-beta', true);
        await setMockOutage('mock-beta', false);

        expect(calls).toEqual(['POST mock-beta', 'DELETE mock-beta']);
    });
});
