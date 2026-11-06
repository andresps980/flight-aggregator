// One itinerary in the results list (AC-7, AC-8): route, times in UTC, segments, prices and providers.
import type { Itinerary } from '../api/types';
import { dayOffset, formatDuration, formatEur, formatUtc, markup, stopsLabel } from '../format';
import { FreshnessBadge } from './FreshnessBadge';

/** "08 Nov 10:15 → 08 Nov 18:40", with "+1" when the arrival is on a later UTC day. */
function TimeRange({ from, to }: { from: string; to: string }) {
  const offset = dayOffset(from, to);
  return (
    <span className="times">
      {formatUtc(from)} → {formatUtc(to)}
      {offset > 0 && <sup className="day-offset">+{offset}</sup>}
    </span>
  );
}

export function ItineraryRow({ itinerary }: { itinerary: Itinerary }) {
  const route = `${itinerary.origin} → ${itinerary.destination}`;
  const others = itinerary.providers.filter((p) => p !== itinerary.selectedProvider);
  return (
    <li className="itinerary" aria-label={`${route}, ${formatEur(itinerary.finalPrice)}`}>
      <div className="itinerary-main">
        <div className="itinerary-title">
          <strong className="route">{route}</strong>
          <span className="carrier">{itinerary.carrier}</span>
          <span>{stopsLabel(itinerary.stops)}</span>
          <span>{formatDuration(itinerary.departureAt, itinerary.arrivalAt)}</span>
          <FreshnessBadge freshness={itinerary.freshness} />
        </div>
        <p className="itinerary-times">
          <TimeRange from={itinerary.departureAt} to={itinerary.arrivalAt} />{' '}
          <abbr title="Coordinated Universal Time">UTC</abbr>
        </p>
        <ol className="segments" aria-label="Segments">
          {itinerary.segments.map((segment) => (
            <li key={`${segment.flightNumber}@${segment.departureAt}`}>
              <span className="flight-number">{segment.flightNumber}</span> {segment.origin} →{' '}
              {segment.destination} <TimeRange from={segment.departureAt} to={segment.arrivalAt} />
            </li>
          ))}
        </ol>
      </div>
      <div className="itinerary-price">
        <span className="price-final">{formatEur(itinerary.finalPrice)}</span>
        <span className="muted">Base {formatEur(itinerary.basePrice)}</span>
        <span className="muted">
          Markup {formatEur(markup(itinerary.basePrice, itinerary.finalPrice))}
        </span>
        <span>Selected: {itinerary.selectedProvider}</span>
        <span className="muted">
          {others.length > 0
            ? `Also offered by: ${others.join(', ')}`
            : `Only offered by ${itinerary.selectedProvider}`}
        </span>
      </div>
    </li>
  );
}
