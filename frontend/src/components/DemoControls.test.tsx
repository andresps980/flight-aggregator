import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { MockProvider } from '../api/types';
import { renderApp } from '../test/render';
import { server } from '../test/server';

function serveMockProviders() {
    const providers: MockProvider[] = [
        { id: 'mock-alpha', latencyMinMs: 100, latencyMaxMs: 5000, errorRate: 0.1, outage: false },
        { id: 'mock-beta', latencyMinMs: 100, latencyMaxMs: 5000, errorRate: 0.1, outage: true },
    ];
    const calls: string[] = [];
    server.use(
        http.get('/gds-admin/providers', () => HttpResponse.json(providers)),
        http.post('/gds-admin/providers/:id/outage', ({ params }) => {
            calls.push(`POST ${String(params.id)}`);
            providers.find((p) => p.id === params.id)!.outage = true;
            return new HttpResponse(null, { status: 204 });
        }),
        http.delete('/gds-admin/providers/:id/outage', ({ params }) => {
            calls.push(`DELETE ${String(params.id)}`);
            providers.find((p) => p.id === params.id)!.outage = false;
            return new HttpResponse(null, { status: 204 });
        }),
    );
    return calls;
}

describe('demo controls (AC-22)', () => {
    it('AC-22: with the flag on, a clearly labelled panel lists the gds-mock providers and their outage state', async () => {
        serveMockProviders();
        renderApp({ demoControls: true });

        const panel = await screen.findByRole('region', { name: /Demo controls/ });
        expect(within(panel).getByRole('heading')).toHaveTextContent(
            'Demo controls — act on gds-mock, not on real providers',
        );
        expect(
            await within(panel).findByRole('switch', { name: 'Outage mock-alpha' }),
        ).not.toBeChecked();
        expect(within(panel).getByRole('switch', { name: 'Outage mock-beta' })).toBeChecked();
        expect(panel).toHaveTextContent(/Refresh data/);
    });

    it('AC-22: toggling a switch starts or ends the provider outage', async () => {
        const calls = serveMockProviders();
        const { user } = renderApp({ demoControls: true });
        const alpha = await screen.findByRole('switch', { name: 'Outage mock-alpha' });
        const beta = screen.getByRole('switch', { name: 'Outage mock-beta' });

        await user.click(alpha);
        await waitFor(() => expect(alpha).toBeChecked());
        await user.click(beta);
        await waitFor(() => expect(beta).not.toBeChecked());

        expect(calls).toEqual(['POST mock-alpha', 'DELETE mock-beta']);
    });

    it('AC-22: an admin API failure is shown in the panel', async () => {
        server.use(http.get('/gds-admin/providers', () => new HttpResponse(null, { status: 404 })));
        renderApp({ demoControls: true });

        const panel = await screen.findByRole('region', { name: /Demo controls/ });
        await waitFor(() => expect(within(panel).getByRole('alert')).toHaveTextContent(/HTTP 404/));
    });

    it('AC-22: with the flag off, the panel is not rendered and gds-mock is never called', async () => {
        // No /gds-admin handler: any call would fail the test (onUnhandledRequest: 'error').
        renderApp({ demoControls: false });

        await screen.findByRole('region', { name: 'Data status' });
        expect(screen.queryByRole('region', { name: /Demo controls/ })).not.toBeInTheDocument();
    });
});
