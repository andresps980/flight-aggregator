// Test data builders typed with the generated API types, so fixtures cannot drift from the contract either.
import type {
  Catalog,
  Freshness,
  IngestionRun,
  Itinerary,
  ItineraryPage,
  ProviderReport,
} from '../api/types';

export const complete: Freshness = {
  status: 'COMPLETE',
  publishedAt: '2026-11-06T10:00:21Z',
  missingProviders: [],
  staleSince: null,
};

export const incomplete: Freshness = {
  status: 'INCOMPLETE',
  publishedAt: '2026-11-06T10:00:21Z',
  missingProviders: ['mock-beta'],
  staleSince: null,
};

export const stale: Freshness = {
  status: 'STALE',
  publishedAt: '2026-11-06T08:00:00Z',
  missingProviders: ['mock-gamma'],
  staleSince: '2026-11-06T10:00:21Z',
};

export const catalog: Catalog = {
  origins: ['BCN', 'MAD'],
  destinations: ['JFK', 'LHR', 'MAD'],
  carriers: ['IB', 'VY'],
  dates: [
    { date: '2026-11-08', freshness: complete },
    { date: '2026-11-09', freshness: incomplete },
    { date: '2026-11-10', freshness: stale },
  ],
  routes: [
    { origin: 'BCN', destinations: ['LHR', 'MAD'] },
    { origin: 'MAD', destinations: ['JFK', 'LHR'] },
  ],
};

let sequence = 0;

/** A direct MAD → JFK itinerary; override any field. Each call gets a distinct key. */
export function itinerary(overrides: Partial<Itinerary> = {}): Itinerary {
  sequence += 1;
  const flightNumber = `IB${6000 + sequence}`;
  return {
    itineraryKey: `${flightNumber}:MAD-JFK@2026-11-08T10:15:00Z`,
    origin: 'MAD',
    destination: 'JFK',
    departureDate: '2026-11-08',
    departureAt: '2026-11-08T10:15:00Z',
    arrivalAt: '2026-11-08T18:40:00Z',
    carrier: 'IB',
    stops: 0,
    segments: [
      {
        flightNumber,
        origin: 'MAD',
        destination: 'JFK',
        departureAt: '2026-11-08T10:15:00Z',
        arrivalAt: '2026-11-08T18:40:00Z',
      },
    ],
    basePrice: 412.38,
    finalPrice: 433.0,
    currency: 'EUR',
    selectedProvider: 'mock-alpha',
    providers: ['mock-alpha', 'mock-gamma'],
    freshness: complete,
    ...overrides,
  };
}

/** A one-stop BCN → LHR → JFK itinerary arriving the next UTC day. */
export function connecting(overrides: Partial<Itinerary> = {}): Itinerary {
  return itinerary({
    itineraryKey: 'VY7001:BCN-LHR@2026-11-08T20:00:00Z|VY7002:LHR-JFK@2026-11-08T22:30:00Z',
    origin: 'BCN',
    destination: 'JFK',
    departureAt: '2026-11-08T20:00:00Z',
    arrivalAt: '2026-11-09T06:05:00Z',
    carrier: 'VY',
    stops: 1,
    segments: [
      {
        flightNumber: 'VY7001',
        origin: 'BCN',
        destination: 'LHR',
        departureAt: '2026-11-08T20:00:00Z',
        arrivalAt: '2026-11-08T22:00:00Z',
      },
      {
        flightNumber: 'VY7002',
        origin: 'LHR',
        destination: 'JFK',
        departureAt: '2026-11-08T22:30:00Z',
        arrivalAt: '2026-11-09T06:05:00Z',
      },
    ],
    basePrice: 250.0,
    finalPrice: 262.5,
    selectedProvider: 'mock-beta',
    providers: ['mock-beta'],
    ...overrides,
  });
}

export function page(items: Itinerary[], nextCursor: string | null = null): ItineraryPage {
  return { items, nextCursor, hasMore: nextCursor !== null };
}

export function providerReport(overrides: Partial<ProviderReport> = {}): ProviderReport {
  return {
    provider: 'mock-alpha',
    status: 'COMPLETED',
    partitionsRequested: 7,
    partitionsSucceeded: 7,
    partitionsFailed: 0,
    failures: [],
    attempts: 8,
    duration: 'PT12.5S',
    durationMs: 12500,
    circuitState: 'CLOSED',
    recordsReceived: 62000,
    offersReceived: 61000,
    malformed: {},
    ...overrides,
  };
}

/** A finished run with a report; `status` RUNNING gives a run without end, duration or report. */
export function run(overrides: Partial<IngestionRun> = {}): IngestionRun {
  const status = overrides.status ?? 'COMPLETED';
  const id = overrides.id ?? 'run-1';
  if (status === 'RUNNING') {
    return {
      id,
      trigger: 'SCHEDULED',
      status,
      startedAt: '2026-11-06T10:00:00Z',
      finishedAt: null,
      durationMs: null,
      failure: null,
      report: null,
      ...overrides,
    };
  }
  return {
    id,
    trigger: 'SCHEDULED',
    status,
    startedAt: '2026-11-06T10:00:00Z',
    finishedAt: '2026-11-06T10:00:17Z',
    durationMs: 17514,
    failure: null,
    report: {
      runId: id,
      status: status === 'PARTIAL' ? 'PARTIAL' : status === 'FAILED' ? 'FAILED' : 'COMPLETED',
      startedAt: '2026-11-06T10:00:00Z',
      finishedAt: '2026-11-06T10:00:17Z',
      duration: 'PT17.514S',
      durationMs: 17514,
      storedItineraries: 94543,
      misfiledItineraries: 0,
      providers: [
        providerReport(),
        providerReport({ provider: 'mock-beta' }),
        providerReport({ provider: 'mock-gamma' }),
      ],
      days: [
        {
          day: '2026-11-08',
          contributing: ['mock-alpha', 'mock-beta', 'mock-gamma'],
          missing: [],
          outcome: 'REPLACED',
          published: true,
          storedItineraries: 13529,
          misfiledItineraries: 0,
          statistics: {
            offersReceivedByProvider: {},
            discardedByReason: {},
            duplicatesMerged: 0,
            itinerariesProduced: 13529,
            totalReceived: 0,
            totalDiscarded: 0,
          },
        },
      ],
      statistics: {
        offersReceivedByProvider: {},
        discardedByReason: {},
        duplicatesMerged: 0,
        itinerariesProduced: 94543,
        totalReceived: 0,
        totalDiscarded: 0,
      },
    },
    ...overrides,
  };
}

export function problem(status: number, title: string, detail: string, extra: object = {}) {
  return {
    type: 'about:blank',
    title,
    status,
    detail,
    instance: '/api/v1/itineraries',
    ...extra,
  };
}
