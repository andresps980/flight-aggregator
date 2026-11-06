// MSW (Mock Service Worker) intercepts the real `fetch` calls made by src/api/client.ts, so tests exercise the
// actual HTTP code. Default handlers describe a healthy backend with some data; tests override them with
// `server.use(...)` for the case at hand. Any request without a handler fails the test (see setup.ts).
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { catalog, page, run } from './fixtures';

export const defaultHandlers = [
    http.get('/api/v1/catalog', () => HttpResponse.json(catalog)),
    http.get('/api/v1/itineraries', () => HttpResponse.json(page([]))),
    http.get('/api/v1/ingestions/latest', () => HttpResponse.json(run())),
    http.get('/config.json', () => HttpResponse.json({ demoControls: false })),
];

export const server = setupServer(...defaultHandlers);

/** Problem Details response, as the aggregator sends it. */
export function problemResponse(body: { status: number; [key: string]: unknown }) {
    return HttpResponse.json(body, {
        status: body.status,
        headers: { 'Content-Type': 'application/problem+json' },
    });
}

/** Records every request matching a path, to assert on what the UI sent. */
export function recordRequests(path: string) {
    const urls: URL[] = [];
    server.events.on('request:start', ({ request }) => {
        const url = new URL(request.url);
        if (url.pathname === path) urls.push(url);
    });
    return urls;
}
