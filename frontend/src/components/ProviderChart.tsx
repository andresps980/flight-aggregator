// Loaded results by selected provider (AC-19, Q6): which provider won each itinerary after deduplication.
// Horizontal bars in plain SVG with the provider name and count as direct labels, plus a table of the figures.
import type { Itinerary } from '../api/types';
import { countByProvider } from '../charts';
import { plural } from '../format';

const WIDTH = 320;
const BAR = 18; // bar thickness (≤ 24px)
const ROW = 30;
const LABEL = 92; // room for the provider name on the left
const VALUE = 36; // room for the count after the bar

/** Fits a provider name into the label column; the full name is in the tooltip and the table. */
function shortLabel(name: string): string {
  return name.length > 13 ? `${name.slice(0, 12)}…` : name;
}

export function ProviderChart({ items }: { items: Itinerary[] }) {
  const counts = countByProvider(items);
  const highest = Math.max(...counts.map((c) => c.count));
  const height = counts.length * ROW;
  const plotWidth = WIDTH - LABEL - VALUE;

  return (
    <figure className="chart" aria-label="Loaded results by selected provider">
      <figcaption>
        Selected provider{' '}
        <span className="muted">
          — of {plural(items.length, 'loaded result', 'loaded results')}
        </span>
      </figcaption>
      <svg viewBox={`0 0 ${WIDTH} ${height}`} role="presentation" aria-hidden="true">
        <line className="baseline" x1={LABEL} x2={LABEL} y1="0" y2={height} />
        {counts.map(({ provider, count }, i) => {
          const y = i * ROW + (ROW - BAR) / 2;
          const width = (count / highest) * plotWidth;
          const r = Math.min(4, width / 2);
          return (
            <g key={provider}>
              <text
                className="axis-label"
                x={LABEL - 6}
                y={y + BAR / 2}
                textAnchor="end"
                dominantBaseline="middle"
              >
                {shortLabel(provider)}
              </text>
              <path
                className="mark"
                d={`M${LABEL},${y} H${LABEL + width - r} Q${LABEL + width},${y} ${LABEL + width},${y + r} V${y + BAR - r} Q${LABEL + width},${y + BAR} ${LABEL + width - r},${y + BAR} H${LABEL} Z`}
              >
                <title>{`${provider}: ${plural(count, 'result', 'results')}`}</title>
              </path>
              <text
                className="value-label"
                x={LABEL + width + 6}
                y={y + BAR / 2}
                dominantBaseline="middle"
              >
                {count}
              </text>
            </g>
          );
        })}
      </svg>
      {/* Hidden but read by screen readers. The class is on a wrapper: overflow does not apply to a table box, so
          a hidden table would still widen the page. */}
      <div className="visually-hidden">
        <table>
          <thead>
            <tr>
              <th scope="col">Provider</th>
              <th scope="col">Results</th>
            </tr>
          </thead>
          <tbody>
            {counts.map(({ provider, count }) => (
              <tr key={provider}>
                <td>{provider}</td>
                <td>{count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </figure>
  );
}
