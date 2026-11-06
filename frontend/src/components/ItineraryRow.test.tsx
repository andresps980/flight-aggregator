import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { connecting, incomplete, itinerary, stale } from '../test/fixtures';
import { FreshnessBadge } from './FreshnessBadge';
import { ItineraryRow } from './ItineraryRow';

function renderRow(...args: Parameters<typeof itinerary>) {
    render(
        <ul>
            <ItineraryRow itinerary={itinerary(...args)} />
        </ul>,
    );
    // The row is the list item named after its route; its segments are a nested list.
    return screen.getByRole('listitem', { name: /→/ });
}

describe('ItineraryRow', () => {
    it('AC-7: shows route, carrier, stops, times, duration, prices, providers and freshness', () => {
        const row = renderRow({ providers: ['mock-alpha', 'mock-beta', 'mock-gamma'] });

        expect(row).toHaveAccessibleName('MAD → JFK, €433.00');
        expect(row).toHaveTextContent('MAD → JFK');
        expect(row).toHaveTextContent('IB');
        expect(row).toHaveTextContent('Direct');
        expect(row).toHaveTextContent('8h 25m');
        expect(within(row).getByText('€433.00')).toHaveClass('price-final');
        expect(row).toHaveTextContent('Base €412.38');
        expect(row).toHaveTextContent('Markup €20.62');
        expect(row).toHaveTextContent('Selected: mock-alpha');
        expect(row).toHaveTextContent('Also offered by: mock-beta, mock-gamma');
        expect(row).toHaveTextContent('COMPLETE');
    });

    it('AC-7: lists every segment with flight number, airports and times', () => {
        render(
            <ul>
                <ItineraryRow itinerary={connecting()} />
            </ul>,
        );
        const segments = within(screen.getByRole('list', { name: 'Segments' })).getAllByRole(
            'listitem',
        );

        expect(segments).toHaveLength(2);
        expect(segments[0]).toHaveTextContent('VY7001');
        expect(segments[0]).toHaveTextContent('BCN → LHR');
        expect(segments[0]).toHaveTextContent('08 Nov 20:00');
        expect(segments[0]).toHaveTextContent('08 Nov 22:00');
        expect(segments[1]).toHaveTextContent('VY7002');
        expect(segments[1]).toHaveTextContent('LHR → JFK');
        expect(screen.getByRole('listitem', { name: /BCN → JFK/ })).toHaveTextContent('1 stop');
    });

    it('AC-7: an itinerary offered by one provider only says so', () => {
        const row = renderRow({ providers: ['mock-alpha'] });

        expect(row).toHaveTextContent('Only offered by mock-alpha');
    });

    it('AC-8: times are labelled UTC and a later-day arrival shows +1', () => {
        render(
            <ul>
                <ItineraryRow itinerary={connecting()} />
            </ul>,
        );
        const row = screen.getByRole('listitem', { name: /BCN → JFK/ });

        expect(row).toHaveTextContent('UTC');
        expect(row).toHaveTextContent('09 Nov 06:05');
        expect(within(row).getAllByText('+1').length).toBeGreaterThan(0);
    });
});

describe('FreshnessBadge', () => {
    it('AC-9: COMPLETE is spelled out', () => {
        render(<FreshnessBadge freshness={itinerary().freshness} />);

        expect(screen.getByText('COMPLETE')).toHaveClass('badge', 'badge-complete');
    });

    it('AC-9: INCOMPLETE names the missing providers in its accessible description', () => {
        render(<FreshnessBadge freshness={incomplete} />);

        const badge = screen.getByText('INCOMPLETE');
        expect(badge).toHaveClass('badge-incomplete');
        expect(badge).toHaveAccessibleDescription('Missing providers: mock-beta');
    });

    it('AC-9: STALE says since when', () => {
        render(<FreshnessBadge freshness={stale} />);

        const badge = screen.getByText('STALE');
        expect(badge).toHaveClass('badge-stale');
        expect(badge).toHaveAccessibleDescription(
            'Stale since 06 Nov 10:00 UTC; missing providers: mock-gamma',
        );
    });
});
