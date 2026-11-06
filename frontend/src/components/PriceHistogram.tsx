// Price distribution of the loaded results (AC-19): a column chart in plain SVG, one hue, with a hidden table of
// the same figures for screen readers and a native tooltip (<title>) on each column.
import type { Itinerary } from '../api/types';
import { priceBuckets } from '../charts';
import { formatEur, plural } from '../format';

const WIDTH = 320;
const HEIGHT = 140;
const AXIS = 18; // room for the min / max labels under the columns
const GAP = 2; // surface gap between adjacent columns

export function PriceHistogram({ items }: { items: Itinerary[] }) {
  const buckets = priceBuckets(items.map((item) => item.finalPrice));
  const highest = Math.max(...buckets.map((b) => b.count));
  const slot = WIDTH / buckets.length;
  const plotHeight = HEIGHT - AXIS;
  const loaded = plural(items.length, 'loaded result', 'loaded results');
  const range = (from: number, to: number) =>
    from === to ? formatEur(from) : `${formatEur(from)} – ${formatEur(to)}`;

  return (
    <figure className="chart" aria-label={`Price distribution of ${loaded}`}>
      <figcaption>
        Final price (EUR) <span className="muted">— of {loaded}</span>
      </figcaption>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="presentation" aria-hidden="true">
        <line className="baseline" x1="0" x2={WIDTH} y1={plotHeight} y2={plotHeight} />
        {buckets.map((bucket, i) => {
          const height = highest === 0 ? 0 : (bucket.count / highest) * (plotHeight - 4);
          const width = Math.min(slot - GAP, 24); // thin columns, capped at 24px
          return (
            <path
              key={i}
              className="mark"
              d={columnPath(i * slot + (slot - width) / 2, plotHeight, width, height)}
            >
              <title>{`${range(bucket.from, bucket.to)}: ${plural(bucket.count, 'result', 'results')}`}</title>
            </path>
          );
        })}
        <text className="axis-label" x="0" y={HEIGHT - 4}>
          {formatEur(buckets[0]?.from ?? 0)}
        </text>
        <text className="axis-label" x={WIDTH} y={HEIGHT - 4} textAnchor="end">
          {formatEur(buckets[buckets.length - 1]?.to ?? 0)}
        </text>
      </svg>
      {/* Hidden but read by screen readers. The class is on a wrapper: overflow does not apply to a table box, so
          a hidden table would still widen the page. */}
      <div className="visually-hidden">
        <table>
          <thead>
            <tr>
              <th scope="col">Price range</th>
              <th scope="col">Results</th>
            </tr>
          </thead>
          <tbody>
            {buckets.map((bucket, i) => (
              <tr key={i}>
                <td>{range(bucket.from, bucket.to)}</td>
                <td>{bucket.count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </figure>
  );
}

/** A column growing up from the baseline, square at the bottom, 4px rounded corners at the top. */
function columnPath(x: number, baseline: number, width: number, height: number): string {
  if (height <= 0) return '';
  const r = Math.min(4, width / 2, height);
  const top = baseline - height;
  return [
    `M${x},${baseline}`,
    `V${top + r}`,
    `Q${x},${top} ${x + r},${top}`,
    `H${x + width - r}`,
    `Q${x + width},${top} ${x + width},${top + r}`,
    `V${baseline}`,
    'Z',
  ].join(' ');
}
