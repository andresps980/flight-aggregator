import { describe, expect, it } from 'vitest';
import { countByProvider, priceBuckets } from './charts';
import { itinerary } from './test/fixtures';

describe('chart data (AC-19)', () => {
    it('AC-19: splits prices into equal-width buckets that cover every price', () => {
        const buckets = priceBuckets([100, 110, 150, 199, 200], 4);

        expect(buckets).toEqual([
            { from: 100, to: 125, count: 2 },
            { from: 125, to: 150, count: 0 },
            { from: 150, to: 175, count: 1 },
            { from: 175, to: 200, count: 2 },
        ]);
        expect(buckets.reduce((sum, b) => sum + b.count, 0)).toBe(5);
    });

    it('AC-19: a single price, or identical prices, give one bucket', () => {
        expect(priceBuckets([433, 433])).toEqual([{ from: 433, to: 433, count: 2 }]);
        expect(priceBuckets([])).toEqual([]);
    });

    it('AC-19: counts loaded results by selected provider, most first, ties by name', () => {
        const items = [
            itinerary({ selectedProvider: 'mock-beta' }),
            itinerary({ selectedProvider: 'mock-alpha' }),
            itinerary({ selectedProvider: 'mock-beta' }),
            itinerary({ selectedProvider: 'mock-gamma' }),
        ];

        expect(countByProvider(items)).toEqual([
            { provider: 'mock-beta', count: 2 },
            { provider: 'mock-alpha', count: 1 },
            { provider: 'mock-gamma', count: 1 },
        ]);
    });
});
