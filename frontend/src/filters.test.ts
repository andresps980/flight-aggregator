import { describe, expect, it } from 'vitest';
import { fromSearchParams, maxPriceError, toSearchParams } from './filters';

describe('filters', () => {
    it('AC-5: only the filters that are set become query parameters, codes uppercase and trimmed', () => {
        const params = toSearchParams({
            origin: ' mad',
            destination: '',
            date: '2026-11-08',
            maxPrice: '650',
            carrier: 'ib',
        });

        expect(params.toString()).toBe('origin=MAD&date=2026-11-08&maxPrice=650&carrier=IB');
    });

    it('AC-5: no filters give no parameters', () => {
        expect(toSearchParams({}).toString()).toBe('');
        expect(toSearchParams({ origin: '  ', maxPrice: '' }).toString()).toBe('');
    });

    it('AC-6: URL parameters round-trip to the same filters, ignoring cursor and unknown names', () => {
        const filters = {
            origin: 'MAD',
            destination: 'JFK',
            date: '2026-11-08',
            maxPrice: '650.5',
            carrier: 'IB',
        };

        const url = toSearchParams(filters);
        url.set('cursor', 'abc');
        url.set('utm_source', 'mail');

        expect(fromSearchParams(url)).toEqual(filters);
        expect(fromSearchParams(new URLSearchParams(''))).toEqual({});
    });

    it('AC-6: a value not in the catalog is kept as typed (the server validates it)', () => {
        expect(fromSearchParams(new URLSearchParams('origin=XYZ&carrier=Q9'))).toEqual({
            origin: 'XYZ',
            carrier: 'Q9',
        });
    });

    it('AC-4: max price must be a non-negative amount below 1,000,000,000 with at most 2 decimals', () => {
        expect(maxPriceError('')).toBeNull();
        expect(maxPriceError('650')).toBeNull();
        expect(maxPriceError('650.5')).toBeNull();
        expect(maxPriceError('0')).toBeNull();
        expect(maxPriceError('999999999.99')).toBeNull();

        expect(maxPriceError('-1')).toMatch(/negative/i);
        expect(maxPriceError('10.123')).toMatch(/2 decimals/i);
        expect(maxPriceError('1000000000')).toMatch(/1,000,000,000/);
        expect(maxPriceError('abc')).toMatch(/number/i);
        expect(maxPriceError('1e3')).toMatch(/number/i);
    });
});
