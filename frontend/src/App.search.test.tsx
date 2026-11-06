import { screen, waitFor, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { catalog, connecting, itinerary, page, problem } from './test/fixtures';
import { catalogLoaded, renderApp, resultRows } from './test/render';
import { problemResponse, recordRequests, server } from './test/server';

function optionLabels(select: HTMLElement) {
  return within(select)
    .getAllByRole('option')
    .map((option) => option.textContent);
}

describe('search panel', () => {
  it('AC-1: fills origin, destination, carrier and date controls from the catalog, each with "Any"', async () => {
    renderApp();
    await catalogLoaded();

    expect(optionLabels(screen.getByLabelText('Origin'))).toEqual(['Any origin', 'BCN', 'MAD']);
    expect(optionLabels(screen.getByLabelText('Destination'))).toEqual([
      'Any destination',
      'JFK',
      'LHR',
      'MAD',
    ]);
    expect(optionLabels(screen.getByLabelText('Carrier'))).toEqual(['Any carrier', 'IB', 'VY']);
    expect(optionLabels(screen.getByLabelText('Departure date (UTC)'))[0]).toBe('Any date');
  });

  it('AC-1: requests the catalog once; controls are disabled while it loads', async () => {
    const urls = recordRequests('/api/v1/catalog');
    server.use(
      http.get('/api/v1/catalog', async () => {
        await delay(50);
        return HttpResponse.json(catalog);
      }),
    );

    renderApp();

    expect(await screen.findByLabelText('Origin')).toBeDisabled();
    await catalogLoaded();
    expect(urls).toHaveLength(1);
  });

  it('AC-1: if the catalog fails, its problem is shown and a search without filters still works', async () => {
    server.use(
      http.get('/api/v1/catalog', () =>
        problemResponse({
          ...problem(500, 'Internal Server Error', 'Catalog unavailable'),
        }),
      ),
      http.get('/api/v1/itineraries', () => HttpResponse.json(page([itinerary()]))),
    );
    const { user } = renderApp();

    expect(await screen.findByText(/Catalog unavailable/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(resultRows()).toHaveLength(1));
  });

  it('AC-2: the destination list depends on the selected origin', async () => {
    const { user } = renderApp();
    await catalogLoaded();

    await user.selectOptions(screen.getByLabelText('Origin'), 'BCN');
    expect(optionLabels(screen.getByLabelText('Destination'))).toEqual([
      'Any destination',
      'LHR',
      'MAD',
    ]);

    await user.selectOptions(screen.getByLabelText('Origin'), 'MAD');
    expect(optionLabels(screen.getByLabelText('Destination'))).toEqual([
      'Any destination',
      'JFK',
      'LHR',
    ]);

    await user.selectOptions(screen.getByLabelText('Origin'), '');
    expect(optionLabels(screen.getByLabelText('Destination'))).toEqual([
      'Any destination',
      'JFK',
      'LHR',
      'MAD',
    ]);
  });

  it('AC-2: changing the origin resets a destination it does not serve, and keeps one it does', async () => {
    const { user } = renderApp();
    await catalogLoaded();
    const destination = screen.getByLabelText('Destination');

    await user.selectOptions(screen.getByLabelText('Origin'), 'MAD');
    await user.selectOptions(destination, 'LHR');
    await user.selectOptions(screen.getByLabelText('Origin'), 'BCN');
    expect(destination).toHaveValue('LHR');

    await user.selectOptions(destination, 'MAD');
    await user.selectOptions(screen.getByLabelText('Origin'), 'MAD');
    expect(destination).toHaveValue('');
  });

  it('AC-3: the date control lists only catalog dates, each with its freshness', async () => {
    renderApp();
    await catalogLoaded();

    expect(optionLabels(screen.getByLabelText('Departure date (UTC)'))).toEqual([
      'Any date',
      '2026-11-08 · COMPLETE',
      '2026-11-09 · INCOMPLETE (missing mock-beta)',
      '2026-11-10 · STALE',
    ]);
  });

  it('AC-4: an invalid max price is flagged next to the field and no search is sent', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    const { user } = renderApp();
    await catalogLoaded();
    await waitFor(() => expect(urls).toHaveLength(1)); // the initial search

    const maxPrice = screen.getByLabelText('Max price (EUR)');
    await user.type(maxPrice, '10.123');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    expect(maxPrice).toHaveAccessibleDescription(/2 decimals/);
    expect(maxPrice).toHaveAttribute('aria-invalid', 'true');
    expect(urls).toHaveLength(1);
  });

  it('AC-4: a server 400 is still shown when the server disagrees with the client checks', async () => {
    server.use(
      http.get('/api/v1/itineraries', ({ request }) =>
        new URL(request.url).searchParams.has('carrier')
          ? problemResponse(problem(400, 'Invalid request', 'carrier must be 2 characters A-Z/0-9'))
          : HttpResponse.json(page([])),
      ),
    );
    window.history.replaceState(null, '', '/?carrier=I%23');
    renderApp();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Invalid request — carrier must be 2 characters A-Z/0-9',
    );
  });

  it('AC-5: submitting sends exactly one request with only the set filters, limit 20 and no cursor', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    const { user } = renderApp();
    await catalogLoaded();
    await waitFor(() => expect(urls).toHaveLength(1));

    await user.selectOptions(screen.getByLabelText('Origin'), 'MAD');
    await user.selectOptions(screen.getByLabelText('Destination'), 'JFK');
    await user.selectOptions(screen.getByLabelText('Carrier'), 'IB');
    await user.type(screen.getByLabelText('Max price (EUR)'), '650');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(urls).toHaveLength(2));
    expect(urls[1]?.search).toBe('?origin=MAD&destination=JFK&maxPrice=650&carrier=IB&limit=20');
  });

  it('AC-5: the date filter is sent as selected; Enter in a field submits the form', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    const { user } = renderApp();
    await catalogLoaded();
    await waitFor(() => expect(urls).toHaveLength(1));

    await user.selectOptions(screen.getByLabelText('Departure date (UTC)'), '2026-11-09');
    await user.type(screen.getByLabelText('Max price (EUR)'), '99.5{Enter}');

    await waitFor(() => expect(urls).toHaveLength(2));
    expect(urls[1]?.search).toBe('?date=2026-11-09&maxPrice=99.5&limit=20');
  });
});

describe('URL state', () => {
  it('AC-6: with no URL parameters the unfiltered search runs at load', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    server.use(
      http.get('/api/v1/itineraries', () => HttpResponse.json(page([itinerary(), connecting()]))),
    );

    renderApp();

    await waitFor(() => expect(resultRows()).toHaveLength(2));
    expect(urls[0]?.search).toBe('?limit=20');
  });

  it('AC-6: a shared URL fills the controls and runs that search immediately', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    window.history.replaceState(
      null,
      '',
      '/?origin=MAD&destination=JFK&maxPrice=650&carrier=IB&date=2026-11-08',
    );

    renderApp();
    await catalogLoaded();

    expect(screen.getByLabelText('Origin')).toHaveValue('MAD');
    expect(screen.getByLabelText('Destination')).toHaveValue('JFK');
    expect(screen.getByLabelText('Max price (EUR)')).toHaveValue(650);
    expect(screen.getByLabelText('Carrier')).toHaveValue('IB');
    expect(screen.getByLabelText('Departure date (UTC)')).toHaveValue('2026-11-08');
    await waitFor(() => expect(urls).toHaveLength(1));
    expect(urls[0]?.search).toBe(
      '?origin=MAD&destination=JFK&date=2026-11-08&maxPrice=650&carrier=IB&limit=20',
    );
  });

  it('AC-6: submitting writes the filters to the URL as a new history entry, without cursor or empty values', async () => {
    const { user } = renderApp();
    await catalogLoaded();
    const entries = window.history.length;

    await user.selectOptions(screen.getByLabelText('Origin'), 'BCN');
    await user.type(screen.getByLabelText('Max price (EUR)'), '300');
    await user.click(screen.getByRole('button', { name: 'Search' }));

    await waitFor(() => expect(window.location.search).toBe('?origin=BCN&maxPrice=300'));
    expect(window.history.length).toBe(entries + 1);
  });

  it('AC-6: back/forward (popstate) restore the previous filters and rerun their search', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    window.history.replaceState(null, '', '/?origin=MAD');
    const { user } = renderApp();
    await catalogLoaded();
    await user.selectOptions(screen.getByLabelText('Origin'), 'BCN');
    await user.click(screen.getByRole('button', { name: 'Search' }));
    await waitFor(() => expect(urls).toHaveLength(2));

    // What the browser does on "back": the URL changes, then popstate fires.
    window.history.replaceState(null, '', '/?origin=MAD');
    window.dispatchEvent(new PopStateEvent('popstate'));

    await waitFor(() => expect(urls).toHaveLength(3));
    expect(urls[2]?.search).toBe('?origin=MAD&limit=20');
    expect(screen.getByLabelText('Origin')).toHaveValue('MAD');
  });

  it('AC-6: a URL value that is not in the catalog is still sent as typed', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    window.history.replaceState(null, '', '/?origin=XYZ');

    renderApp();

    await waitFor(() => expect(urls).toHaveLength(1));
    expect(urls[0]?.search).toBe('?origin=XYZ&limit=20');
  });
});

describe('results states', () => {
  it('AC-10: no results shows a clear message instead of an empty list', async () => {
    renderApp();

    expect(await screen.findByText('No itineraries match these filters')).toBeInTheDocument();
    expect(screen.queryByRole('list', { name: 'Itineraries' })).not.toBeInTheDocument();
  });

  it('AC-11: while a search is in flight the button is busy and a second submit is ignored', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    server.use(
      http.get('/api/v1/itineraries', async () => {
        await delay(100);
        return HttpResponse.json(page([itinerary()]));
      }),
    );
    const { user } = renderApp();
    await catalogLoaded();

    const button = screen.getByRole('button', { name: /Search/ });
    expect(button).toHaveAttribute('aria-busy', 'true');
    await user.click(button);

    await waitFor(() => expect(resultRows()).toHaveLength(1));
    expect(button).toHaveAttribute('aria-busy', 'false');
    expect(urls).toHaveLength(1);
  });

  it('AC-11: the answer of a superseded search is never displayed', async () => {
    server.use(
      http.get('/api/v1/itineraries', async ({ request }) => {
        const origin = new URL(request.url).searchParams.get('origin');
        if (origin === 'MAD') {
          await delay(200); // the first search answers last
          return HttpResponse.json(page([itinerary({ origin: 'MAD' })]));
        }
        return HttpResponse.json(page([connecting(), connecting({ itineraryKey: 'other' })]));
      }),
    );
    window.history.replaceState(null, '', '/?origin=MAD');
    renderApp();
    await catalogLoaded();

    // Back to an unfiltered search while the MAD one is still in flight.
    window.history.replaceState(null, '', '/?origin=BCN');
    window.dispatchEvent(new PopStateEvent('popstate'));

    await waitFor(() => expect(resultRows()).toHaveLength(2));
    await delay(250);
    expect(resultRows()).toHaveLength(2);
    expect(resultRows()[0]).toHaveAccessibleName(/BCN → JFK/);
  });

  it('AC-20: a search error shows the problem title and detail in an alert, and no spinner remains', async () => {
    server.use(
      http.get('/api/v1/itineraries', () =>
        problemResponse(
          problem(400, 'Invalid request', 'origin must be a 3-letter IATA airport code'),
        ),
      ),
    );

    renderApp();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Invalid request — origin must be a 3-letter IATA airport code',
    );
    expect(screen.getByRole('button', { name: /Search/ })).toHaveAttribute('aria-busy', 'false');
  });

  it('AC-20: a non-problem error (502 while the aggregator restarts) shows a generic message with the status', async () => {
    server.use(
      http.get('/api/v1/itineraries', () => new HttpResponse('Bad Gateway', { status: 502 })),
    );

    renderApp();

    expect(await screen.findByRole('alert')).toHaveTextContent(/HTTP 502/);
  });
});
