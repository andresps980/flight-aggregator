// The only module that talks HTTP to the backend (ADR-0024). One small function per endpoint, typed with the
// types generated from docs/api/openapi.json. Every failure becomes an ApiError with a readable message; an
// RFC 9457 problem body is kept so the UI can show its title and detail (spec 006 AC-20).
import { toSearchParams, type Filters } from '../filters';
import type {
  Catalog,
  IngestionAlreadyRunningProblem,
  IngestionRun,
  ItineraryPage,
  MockProvider,
  ProblemDetail,
} from './types';

/** Requests give up after this long (spec 006 NFR); the UI then shows a "timed out" error. */
export const REQUEST_TIMEOUT_MS = 10_000;

export interface RequestOptions {
  /** Lets the caller cancel a request it no longer needs (superseded search, unmounted component). */
  signal?: AbortSignal;
  timeoutMs?: number;
}

/** A failed request. `status` is 0 when no HTTP answer arrived (network failure, timeout). */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: ProblemDetail | null;

  constructor(message: string, status: number, problem: ProblemDetail | null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }
}

/** Any thrown value as an ApiError, for code that only wants a message to show. */
export function toApiError(error: unknown): ApiError {
  return error instanceof ApiError ? error : new ApiError(String(error), 0, null);
}

/**
 * Sends a request and returns the parsed JSON body (undefined for an empty body). Rejects with an ApiError for
 * HTTP errors, network failures, timeouts and invalid JSON; with the browser's AbortError when the caller aborted.
 */
async function send<T>(
  path: string,
  init: RequestInit = {},
  options: RequestOptions = {},
): Promise<T> {
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), options.timeoutMs ?? REQUEST_TIMEOUT_MS);
  const signal = options.signal
    ? AbortSignal.any([options.signal, timeout.signal])
    : timeout.signal;
  try {
    let response: Response;
    let text: string;
    try {
      // Absolute URL: same origin as the page (nginx in production, the Vite proxy in development).
      response = await fetch(new URL(path, window.location.href), {
        ...init,
        headers: { ...init.headers, Accept: 'application/json, application/problem+json' },
        signal,
      });
      text = await response.text();
    } catch (error) {
      if (options.signal?.aborted) throw error;
      if (timeout.signal.aborted) throw new ApiError('The request timed out', 0, null);
      throw new ApiError('Could not reach the server', 0, null);
    }

    if (!response.ok) throw httpError(response, text);
    if (text === '') return undefined as T;
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new ApiError(
        `The server sent an invalid response (HTTP ${response.status})`,
        response.status,
        null,
      );
    }
  } finally {
    clearTimeout(timer);
  }
}

/** An HTTP error: the problem's "title — detail" when the body is RFC 9457, else a generic message. */
function httpError(response: Response, text: string): ApiError {
  const isProblem =
    response.headers.get('Content-Type')?.includes('application/problem+json') ?? false;
  if (isProblem) {
    try {
      const problem = JSON.parse(text) as ProblemDetail;
      const message = [problem.title, problem.detail].filter(Boolean).join(' — ');
      return new ApiError(
        message || `Request failed (HTTP ${response.status})`,
        response.status,
        problem,
      );
    } catch {
      // Fall through to the generic message.
    }
  }
  return new ApiError(`Request failed (HTTP ${response.status})`, response.status, null);
}

/** `GET /api/v1/catalog`: what can be searched (spec 005 AC-17). */
export function getCatalog(options?: RequestOptions): Promise<Catalog> {
  return send<Catalog>('/api/v1/catalog', {}, options);
}

/** `GET /api/v1/itineraries`: one page of results, cheapest first (spec 005). */
export function searchItineraries(
  filters: Filters,
  page: { limit: number; cursor?: string | null },
  options?: RequestOptions,
): Promise<ItineraryPage> {
  const params = toSearchParams(filters);
  params.set('limit', String(page.limit));
  if (page.cursor) params.set('cursor', page.cursor);
  return send<ItineraryPage>(`/api/v1/itineraries?${params}`, {}, options);
}

/** `GET /api/v1/ingestions/latest`; null when no run exists yet (404). */
export async function getLatestIngestion(options?: RequestOptions): Promise<IngestionRun | null> {
  try {
    return await send<IngestionRun>('/api/v1/ingestions/latest', {}, options);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) return null;
    throw error;
  }
}

export type StartOutcome =
  { kind: 'started'; run: IngestionRun } | { kind: 'conflict'; runningIngestionId: string | null };

/** `POST /api/v1/ingestions`: 202 = started; 409 = a run is already in progress (ADR-0006). */
export async function startIngestion(options?: RequestOptions): Promise<StartOutcome> {
  try {
    const run = await send<IngestionRun>('/api/v1/ingestions', { method: 'POST' }, options);
    return { kind: 'started', run };
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      const problem = error.problem as Partial<IngestionAlreadyRunningProblem> | null;
      return { kind: 'conflict', runningIngestionId: problem?.runningIngestionId ?? null };
    }
    throw error;
  }
}

// Demo controls (spec 006 AC-22): gds-mock's admin API, proxied by nginx under /gds-admin only when enabled.

export function getMockProviders(options?: RequestOptions): Promise<MockProvider[]> {
  return send<MockProvider[]>('/gds-admin/providers', {}, options);
}

export async function setMockOutage(
  providerId: string,
  down: boolean,
  options?: RequestOptions,
): Promise<void> {
  const path = `/gds-admin/providers/${encodeURIComponent(providerId)}/outage`;
  await send<void>(path, { method: down ? 'POST' : 'DELETE' }, options);
}
