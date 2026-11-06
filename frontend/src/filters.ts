// The search filters: the same five names as the API's query parameters (spec 005), so the page URL, the form and
// the API request all use one vocabulary (spec 006 AC-5, AC-6).

export interface Filters {
  origin?: string;
  destination?: string;
  date?: string;
  /** Kept as typed (`"100.00"`); the server normalises it. */
  maxPrice?: string;
  carrier?: string;
}

const NAMES = ['origin', 'destination', 'date', 'maxPrice', 'carrier'] as const;
const CODES = new Set<string>(['origin', 'destination', 'carrier']);

/** Only the filters that are set, trimmed, airport and airline codes uppercased. */
export function toSearchParams(filters: Filters): URLSearchParams {
  const params = new URLSearchParams();
  for (const name of NAMES) {
    const value = filters[name]?.trim();
    if (value) params.set(name, CODES.has(name) ? value.toUpperCase() : value);
  }
  return params;
}

/** The filters in a URL query; anything else (a cursor, tracking parameters) is ignored. */
export function fromSearchParams(params: URLSearchParams): Filters {
  const filters: Filters = {};
  for (const name of NAMES) {
    const value = params.get(name)?.trim();
    if (value) filters[name] = value;
  }
  return filters;
}

/**
 * Client-side check of the max price, mirroring the API's rules (spec 005 AC-13) so obvious mistakes are flagged
 * next to the field; the server stays authoritative. Returns the message to show, or null if the value is fine.
 */
export function maxPriceError(value: string): string | null {
  const text = value.trim();
  if (text === '') return null;
  if (text.startsWith('-')) return 'Max price must not be negative';
  if (!/^\d+(\.\d+)?$/.test(text)) return 'Max price must be a number, e.g. 650 or 650.50';
  if ((text.split('.')[1] ?? '').length > 2) return 'Max price must have at most 2 decimals';
  if (Number(text) >= 1_000_000_000) return 'Max price must be below 1,000,000,000';
  return null;
}
