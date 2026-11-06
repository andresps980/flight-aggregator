// Display formatting. Every time is shown in UTC (spec 006 AC-8: the API sends UTC instants, no airport offsets),
// built from the UTC getters so the result never depends on the machine's time zone or locale.

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DAY_MS = 24 * 60 * 60 * 1000;

const pad = (value: number) => String(value).padStart(2, '0');

/** `2026-11-08T10:15:00Z` → `08 Nov 10:15` (UTC; callers add the "UTC" label once per block). */
export function formatUtc(instant: string): string {
  const date = new Date(instant);
  return `${pad(date.getUTCDate())} ${MONTHS[date.getUTCMonth()]} ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())}`;
}

/** How many UTC calendar days `to` is after `from` (0 = same day); shown as `+1`, `+2`. */
export function dayOffset(from: string, to: string): number {
  const startOfDay = (instant: string) => Math.floor(new Date(instant).getTime() / DAY_MS);
  return startOfDay(to) - startOfDay(from);
}

/** Elapsed time between two instants as `8h 25m`. */
export function formatDuration(from: string, to: string): string {
  const minutes = Math.round((new Date(to).getTime() - new Date(from).getTime()) / 60_000);
  return `${Math.floor(minutes / 60)}h ${pad(minutes % 60)}m`;
}

const eur = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'EUR' });

/** `1234.5` → `€1,234.50`. */
export function formatEur(amount: number): string {
  return eur.format(amount);
}

/**
 * The OTA markup (`final − base`). Prices are JSON numbers with 2 decimals; subtracting them as floats can give
 * `0.19999999999999998`, so the difference is computed in whole cents.
 */
export function markup(basePrice: number, finalPrice: number): number {
  return (Math.round(finalPrice * 100) - Math.round(basePrice * 100)) / 100;
}

export function stopsLabel(stops: number): string {
  if (stops === 0) return 'Direct';
  return stops === 1 ? '1 stop' : `${stops} stops`;
}

/** `3` → `3 itineraries`, `1` → `1 itinerary` style pluralisation. */
export function plural(count: number, singular: string, pluralForm: string): string {
  return `${count.toLocaleString('en-GB')} ${count === 1 ? singular : pluralForm}`;
}
