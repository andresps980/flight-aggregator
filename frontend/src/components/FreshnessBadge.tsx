// The freshness of an itinerary's departure day (AC-9): the status in words, details as an accessible description.
import { useId } from 'react';
import type { Freshness } from '../api/types';
import { formatUtc } from '../format';

function describe(freshness: Freshness): string | null {
  const missing = freshness.missingProviders.join(', ');
  if (freshness.status === 'STALE' && freshness.staleSince) {
    const since = `Stale since ${formatUtc(freshness.staleSince)} UTC`;
    return missing ? `${since}; missing providers: ${missing}` : since;
  }
  if (freshness.status === 'INCOMPLETE') return `Missing providers: ${missing}`;
  return null;
}

export function FreshnessBadge({ freshness }: { freshness: Freshness }) {
  const id = useId();
  const description = describe(freshness);
  return (
    <>
      <span
        className={`badge badge-${freshness.status.toLowerCase()}`}
        aria-describedby={description ? id : undefined}
        title={description ?? undefined}
      >
        {freshness.status}
      </span>
      {description && (
        <span id={id} className="visually-hidden">
          {description}
        </span>
      )}
    </>
  );
}
