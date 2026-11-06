// The search form (AC-1 – AC-5): dropdowns from the catalog, destinations depending on the origin, dates with
// their freshness, a checked max price. It edits a local copy of the filters and hands them over on submit.
// The parent gives it a `key` derived from the URL filters, so back/forward remounts it with the new values.
import { useState, type FormEvent } from 'react';
import type { Catalog, CatalogDate } from '../api/types';
import { maxPriceError, type Filters } from '../filters';

interface Props {
  catalog: Catalog | null;
  catalogLoading: boolean;
  /** The current (URL) filters: the form's initial values. */
  filters: Filters;
  busy: boolean;
  onSearch: (filters: Filters) => void;
}

/** The options of a select; a current value missing from the list (e.g. from a shared URL) is kept as typed. */
function withCurrent(values: string[], current: string | undefined): string[] {
  return current && !values.includes(current) ? [...values, current] : values;
}

function dateLabel({ date, freshness }: CatalogDate): string {
  if (freshness.status === 'INCOMPLETE') {
    return `${date} · INCOMPLETE (missing ${freshness.missingProviders.join(', ')})`;
  }
  return `${date} · ${freshness.status}`;
}

function destinationsFor(catalog: Catalog | null, origin: string | undefined): string[] {
  if (!catalog) return [];
  if (!origin) return catalog.destinations;
  return catalog.routes.find((route) => route.origin === origin)?.destinations ?? [];
}

export function SearchPanel({ catalog, catalogLoading, filters, busy, onSearch }: Props) {
  const [form, setForm] = useState<Filters>(filters);
  const [priceError, setPriceError] = useState<string | null>(null);

  const destinations = destinationsFor(catalog, form.origin);

  const set = (name: keyof Filters, value: string) =>
    setForm((current) => ({ ...current, [name]: value }));

  const changeOrigin = (origin: string) =>
    setForm((current) => {
      // Keep the destination only if the new origin flies there (AC-2).
      const served = destinationsFor(catalog, origin);
      const destination =
        current.destination && served.includes(current.destination) ? current.destination : '';
      return { ...current, origin, destination };
    });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const error = maxPriceError(form.maxPrice ?? '');
    setPriceError(error);
    if (error || busy) return; // AC-4; AC-11: a second submit while searching is ignored
    onSearch(form);
  };

  const disabled = catalogLoading;
  return (
    <form className="search-panel" onSubmit={submit} noValidate aria-label="Search itineraries">
      <div className="field">
        <label htmlFor="origin">Origin</label>
        <select
          id="origin"
          value={form.origin ?? ''}
          disabled={disabled}
          onChange={(e) => changeOrigin(e.target.value)}
        >
          <option value="">Any origin</option>
          {withCurrent(catalog?.origins ?? [], form.origin).map((code) => (
            <option key={code}>{code}</option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="destination">Destination</label>
        <select
          id="destination"
          value={form.destination ?? ''}
          disabled={disabled}
          onChange={(e) => set('destination', e.target.value)}
        >
          <option value="">Any destination</option>
          {withCurrent(destinations, form.destination).map((code) => (
            <option key={code}>{code}</option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="date">Departure date (UTC)</label>
        <select
          id="date"
          value={form.date ?? ''}
          disabled={disabled}
          onChange={(e) => set('date', e.target.value)}
        >
          <option value="">Any date</option>
          {catalog?.dates.map((entry) => (
            <option key={entry.date} value={entry.date}>
              {dateLabel(entry)}
            </option>
          ))}
          {form.date && !catalog?.dates.some((d) => d.date === form.date) && (
            <option>{form.date}</option>
          )}
        </select>
      </div>
      <div className="field">
        <label htmlFor="maxPrice">Max price (EUR)</label>
        <input
          id="maxPrice"
          // Text, not type="number": a number input hides what was typed when it is not a number (the value is
          // then ""), so a typo would silently search without a price limit. The format is checked on submit.
          type="text"
          inputMode="decimal"
          autoComplete="off"
          value={form.maxPrice ?? ''}
          aria-invalid={priceError ? 'true' : 'false'}
          aria-describedby={priceError ? 'maxPrice-error' : undefined}
          onChange={(e) => set('maxPrice', e.target.value)}
        />
        {priceError && (
          <p id="maxPrice-error" className="field-error" role="alert">
            {priceError}
          </p>
        )}
      </div>
      <div className="field">
        <label htmlFor="carrier">Carrier</label>
        <select
          id="carrier"
          value={form.carrier ?? ''}
          disabled={disabled}
          onChange={(e) => set('carrier', e.target.value)}
        >
          <option value="">Any carrier</option>
          {withCurrent(catalog?.carriers ?? [], form.carrier).map((code) => (
            <option key={code}>{code}</option>
          ))}
        </select>
      </div>
      <button type="submit" className="primary" aria-busy={busy}>
        Search
      </button>
    </form>
  );
}
