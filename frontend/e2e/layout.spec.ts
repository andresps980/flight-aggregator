// Layout regression check (spec 006, a bug found by hand): at several widths the dashboard's panels must never
// overlap, nothing may stick out of its panel, and the page must not scroll sideways. The API is mocked with
// deliberately wide content (long provider names, three-segment itineraries, failures) to stress the layout.
import { expect, test, type Page } from '@playwright/test';
import {
  catalog,
  connecting,
  incomplete,
  itinerary,
  page as pageOf,
  providerReport,
  run,
  stale,
} from '../src/test/fixtures';

const LONG_PROVIDER = 'mock-provider-with-a-very-long-identifier';

function wideItinerary(i: number) {
  return itinerary({
    itineraryKey: `wide-${i}`,
    origin: 'MAD',
    destination: 'SYD',
    stops: 2,
    arrivalAt: '2026-11-10T07:45:00Z',
    segments: [
      {
        flightNumber: 'QF1001',
        origin: 'MAD',
        destination: 'DXB',
        departureAt: '2026-11-08T10:15:00Z',
        arrivalAt: '2026-11-08T19:40:00Z',
      },
      {
        flightNumber: 'QF1002',
        origin: 'DXB',
        destination: 'SIN',
        departureAt: '2026-11-08T22:10:00Z',
        arrivalAt: '2026-11-09T09:30:00Z',
      },
      {
        flightNumber: 'QF1003',
        origin: 'SIN',
        destination: 'SYD',
        departureAt: '2026-11-09T12:00:00Z',
        arrivalAt: '2026-11-10T07:45:00Z',
      },
    ],
    finalPrice: 1234567.89 - i,
    basePrice: 1175778.94,
    selectedProvider: i % 2 ? LONG_PROVIDER : 'mock-alpha',
    providers: ['mock-alpha', 'mock-beta', 'mock-gamma', LONG_PROVIDER],
    freshness: i % 3 === 0 ? stale : i % 3 === 1 ? incomplete : itinerary().freshness,
  });
}

async function mockApi(page: Page) {
  const latest = run({ status: 'PARTIAL', failure: null });
  latest.report!.providers = [
    providerReport(),
    providerReport({
      provider: LONG_PROVIDER,
      status: 'FAILED',
      partitionsSucceeded: 0,
      partitionsFailed: 7,
      circuitState: 'HALF_OPEN',
      failures: Array.from({ length: 7 }, (_, d) => ({
        day: `2026-11-0${d + 1}`,
        kind: 'CIRCUIT_OPEN' as const,
        attempts: 0,
        detail: 'breaker open',
      })),
    }),
    providerReport({ provider: 'mock-gamma', circuitState: 'OPEN' }),
  ];
  const wideCatalog = {
    ...catalog,
    dates: catalog.dates.map((d) => ({
      ...d,
      freshness: { ...d.freshness, missingProviders: [LONG_PROVIDER, 'mock-beta'] },
    })),
  };
  const items = [...Array.from({ length: 12 }, (_, i) => wideItinerary(i)), connecting()];

  await page.route('**/config.json', (r) => r.fulfill({ json: { demoControls: true } }));
  await page.route('**/api/v1/catalog', (r) => r.fulfill({ json: wideCatalog }));
  await page.route('**/api/v1/itineraries**', (r) => r.fulfill({ json: pageOf(items, 'next') }));
  await page.route('**/api/v1/ingestions/latest', (r) => r.fulfill({ json: latest }));
  await page.route('**/gds-admin/providers', (r) =>
    r.fulfill({
      json: ['mock-alpha', LONG_PROVIDER].map((id) => ({
        id,
        latencyMinMs: 100,
        latencyMaxMs: 5000,
        errorRate: 0.1,
        outage: false,
      })),
    }),
  );
}

/** Runs in the page: the layout problems found, as readable strings (empty = fine). */
function layoutProblems(): string[] {
  const problems: string[] = [];
  const describe = (el: Element) =>
    `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}${el.className && typeof el.className === 'string' ? `.${el.className.split(' ').join('.')}` : ''}`;
  const panels = [
    ...document.querySelectorAll('.area-search, .area-status, .area-demo, .area-results'),
  ];
  const boxes = panels.map((p) => p.getBoundingClientRect());
  const tolerance = 1;

  // 1. Panels do not intersect each other.
  for (let i = 0; i < panels.length; i++) {
    for (let j = i + 1; j < panels.length; j++) {
      const a = boxes[i]!;
      const b = boxes[j]!;
      const overlap =
        a.left < b.right - tolerance &&
        b.left < a.right - tolerance &&
        a.top < b.bottom - tolerance &&
        b.top < a.bottom - tolerance;
      if (overlap) problems.push(`${describe(panels[i]!)} overlaps ${describe(panels[j]!)}`);
    }
  }

  // 2. Nothing visible sticks out of its panel. A scroll/clip container is checked itself, not its content; visually
  //    hidden helpers (screen-reader tables) are skipped.
  panels.forEach((panel, index) => {
    const box = boxes[index]!;
    const visit = (el: Element) => {
      if (el.classList.contains('visually-hidden')) return;
      const rect = el.getBoundingClientRect();
      if (rect.width > 0 && rect.height > 0) {
        if (rect.left < box.left - tolerance || rect.right > box.right + tolerance) {
          problems.push(
            `${describe(el)} sticks out of ${describe(panel)} (${Math.round(rect.left)}–${Math.round(rect.right)} vs ${Math.round(box.left)}–${Math.round(box.right)})`,
          );
          return;
        }
      }
      const overflowX = getComputedStyle(el).overflowX;
      if (overflowX === 'visible' || el === panel) [...el.children].forEach(visit);
    };
    [...panel.children].forEach(visit);
  });

  // 3. No sideways scrolling of the page.
  if (document.documentElement.scrollWidth > window.innerWidth + tolerance) {
    // Name the elements that reach past the window (deepest first), so a failure says what to fix.
    const culprits = [...document.querySelectorAll('body *')]
      .filter((el) => el.getBoundingClientRect().right > window.innerWidth + tolerance)
      .filter(
        (el) =>
          ![...el.children].some(
            (child) => child.getBoundingClientRect().right > window.innerWidth + tolerance,
          ),
      )
      .slice(0, 5)
      .map(
        (el) => `${describe(el)} (right edge ${Math.round(el.getBoundingClientRect().right)}px)`,
      );
    problems.push(
      `page scrolls horizontally: ${document.documentElement.scrollWidth}px content in a ${window.innerWidth}px window; ` +
        `widest: ${culprits.join(', ')}`,
    );
  }
  return problems;
}

for (const width of [1440, 1170, 1024, 768]) {
  test(`panels never overlap or overflow at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await mockApi(page);

    await page.goto('/?origin=MAD');
    await expect(page.getByRole('list', { name: 'Itineraries' })).toBeVisible();
    await expect(page.getByRole('region', { name: /Demo controls/ })).toContainText(LONG_PROVIDER);
    await expect(
      page.getByRole('row', { name: new RegExp(`${LONG_PROVIDER} FAILED`) }),
    ).toBeVisible();
    // Open the per-day list too (programmatically: a click would time out on exactly the overlap we test for).
    await page
      .locator('.area-status details')
      .evaluate((el) => ((el as HTMLDetailsElement).open = true));
    await expect(page.locator('.area-search, .area-status, .area-demo, .area-results')).toHaveCount(
      4,
    );

    expect(await page.evaluate(layoutProblems)).toEqual([]);
  });
}

test('below the breakpoint, the status panel is stacked under the results', async ({ page }) => {
  await page.setViewportSize({ width: 768, height: 900 });
  await mockApi(page);
  await page.goto('/');
  await expect(page.getByRole('list', { name: 'Itineraries' })).toBeVisible();

  const results = await page.locator('.area-results').boundingBox();
  const status = await page.locator('.area-status').boundingBox();
  expect(status!.y).toBeGreaterThanOrEqual(results!.y + results!.height);
});
