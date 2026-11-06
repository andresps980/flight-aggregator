import { render, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { itinerary, page } from '../test/fixtures';
import { renderApp, resultRows } from '../test/render';
import { server } from '../test/server';
import { PriceHistogram } from './PriceHistogram';
import { ProviderChart } from './ProviderChart';

const items = [
  itinerary({ finalPrice: 100, selectedProvider: 'mock-alpha' }),
  itinerary({ finalPrice: 150, selectedProvider: 'mock-beta' }),
  itinerary({ finalPrice: 200, selectedProvider: 'mock-beta' }),
];

describe('visualisation (AC-19)', () => {
  it('AC-19: the price histogram is an SVG labelled with the number of loaded results and a figures table', () => {
    render(<PriceHistogram items={items} />);

    const figure = screen.getByRole('figure', {
      name: 'Price distribution of 3 loaded results',
    });
    expect(figure.querySelector('svg')).not.toBeNull();
    expect(figure).toHaveTextContent('of 3 loaded results');
    const table = within(figure).getByRole('table');
    expect(within(table).getAllByRole('row').length).toBeGreaterThan(1);
    expect(table).toHaveTextContent('€100.00');
  });

  it('AC-19: results by selected provider show each provider with its count', () => {
    render(<ProviderChart items={items} />);

    const figure = screen.getByRole('figure', { name: 'Loaded results by selected provider' });
    expect(figure.querySelector('svg')).not.toBeNull();
    const rows = within(within(figure).getByRole('table')).getAllByRole('row');
    expect(rows.map((row) => row.textContent)).toEqual([
      'ProviderResults',
      'mock-beta2',
      'mock-alpha1',
    ]);
  });

  it('AC-19: the charts are hidden when nothing is loaded', async () => {
    renderApp();

    await screen.findByText('No itineraries match these filters');
    expect(screen.queryByRole('figure')).not.toBeInTheDocument();
  });

  it('AC-19: the charts update as pages are appended', async () => {
    server.use(
      http.get('/api/v1/itineraries', ({ request }) =>
        HttpResponse.json(
          new URL(request.url).searchParams.has('cursor') ? page([itinerary()]) : page(items, 'c1'),
        ),
      ),
    );
    const { user } = renderApp();
    expect(
      await screen.findByRole('figure', { name: 'Price distribution of 3 loaded results' }),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Load more' }));

    await waitFor(() => expect(resultRows()).toHaveLength(4));
    expect(
      screen.getByRole('figure', { name: 'Price distribution of 4 loaded results' }),
    ).toBeInTheDocument();
  });
});
