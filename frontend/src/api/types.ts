// Short names for the API types generated from docs/api/openapi.json (src/api/schema.d.ts, `npm run gen:api`).
// Never write these shapes by hand: if the backend contract changes, regenerate and let `tsc` show what broke.
import type { components } from './schema';

type Schemas = components['schemas'];

export type Catalog = Schemas['CatalogResponse'];
export type CatalogDate = Schemas['CatalogDateResponse'];
export type Freshness = Schemas['FreshnessResponse'];
export type FreshnessStatus = Freshness['status'];
export type Itinerary = Schemas['ItineraryResponse'];
export type Segment = Schemas['SegmentResponse'];
export type ItineraryPage = Schemas['ItineraryPageResponse'];
export type IngestionRun = Schemas['IngestionRunResponse'];
export type RunStatus = IngestionRun['status'];
export type IngestionReport = Schemas['ReportDocument'];
export type ProviderReport = Schemas['ProviderDocument'];
export type DayReport = Schemas['DayDocument'];
export type ProblemDetail = Schemas['ProblemDetail'];
export type IngestionAlreadyRunningProblem = Schemas['IngestionAlreadyRunningProblem'];

/** One gds-mock provider from `GET /admin/providers` (demo controls; gds-mock has no OpenAPI description). */
export interface MockProvider {
  id: string;
  latencyMinMs: number;
  latencyMaxMs: number;
  errorRate: number;
  outage: boolean;
}
