import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { loadConfig } from './config';
import { server } from './test/server';

describe('runtime config (AC-22)', () => {
    it('AC-22: reads demoControls from /config.json', async () => {
        server.use(http.get('/config.json', () => HttpResponse.json({ demoControls: true })));

        expect(await loadConfig()).toEqual({ demoControls: true });
    });

    it.each([
        ['missing file', () => new HttpResponse(null, { status: 404 })],
        [
            'invalid JSON',
            () => new HttpResponse('not json', { headers: { 'Content-Type': 'application/json' } }),
        ],
        ['wrong type', () => HttpResponse.json({ demoControls: 'yes' })],
        ['network error', () => HttpResponse.error()],
    ])('AC-22: demo controls are off when the config is a %s', async (_, resolver) => {
        server.use(http.get('/config.json', resolver));

        expect(await loadConfig()).toEqual({ demoControls: false });
    });
});
