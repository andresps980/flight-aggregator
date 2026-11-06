// Data for the two charts (spec 006 AC-19), computed from the loaded results only (there is no total count).
import type { Itinerary } from './api/types';

export interface PriceBucket {
  from: number;
  to: number;
  count: number;
}

const cents = (value: number) => Math.round(value * 100) / 100;

/** Equal-width price buckets from the lowest to the highest price; the highest price falls in the last bucket. */
export function priceBuckets(prices: number[], count = 10): PriceBucket[] {
  if (prices.length === 0) return [];
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  if (min === max) return [{ from: min, to: max, count: prices.length }];

  const width = (max - min) / count;
  const buckets = Array.from({ length: count }, (_, i) => ({
    from: cents(min + i * width),
    to: cents(i === count - 1 ? max : min + (i + 1) * width),
    count: 0,
  }));
  for (const price of prices) {
    const index = Math.min(count - 1, Math.floor((price - min) / width));
    buckets[index]!.count += 1;
  }
  return buckets;
}

export interface ProviderCount {
  provider: string;
  count: number;
}

/** How many loaded itineraries each provider won (selected offer), most first, ties by name. */
export function countByProvider(items: Itinerary[]): ProviderCount[] {
  const counts = new Map<string, number>();
  for (const item of items)
    counts.set(item.selectedProvider, (counts.get(item.selectedProvider) ?? 0) + 1);
  return [...counts]
    .map(([provider, count]) => ({ provider, count }))
    .sort((a, b) => b.count - a.count || a.provider.localeCompare(b.provider));
}
