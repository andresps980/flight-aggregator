import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import type { ItineraryPage } from './api/types';
import { itinerary, page, problem } from './test/fixtures';
import { renderApp, resultRows } from './test/render';
import { problemResponse, recordRequests, server } from './test/server';

/** Serves the given pages in order: the first without cursor, page n+1 for cursor `c<n>`. */
function servePages(pages: ItineraryPage[]) {
  server.use(
    http.get('/api/v1/itineraries', ({ request }) => {
      const cursor = new URL(request.url).searchParams.get('cursor');
      const index = cursor === null ? 0 : Number(cursor.slice(1));
      return HttpResponse.json(pages[index]);
    }),
  );
}

describe('pagination', () => {
  it('AC-12: "Load more" requests the same filters with nextCursor and appends the items', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    servePages([page([itinerary(), itinerary()], 'c1'), page([itinerary()])]);
    window.history.replaceState(null, '', '/?origin=MAD');
    const { user } = renderApp();
    await waitFor(() => expect(resultRows()).toHaveLength(2));

    await user.click(screen.getByRole('button', { name: 'Load more' }));

    await waitFor(() => expect(resultRows()).toHaveLength(3));
    expect(urls[1]?.search).toBe('?origin=MAD&limit=20&cursor=c1');
  });

  it('AC-12: shows how many are loaded, no total, and "End of results" when hasMore is false', async () => {
    servePages([page([itinerary(), itinerary()], 'c1'), page([itinerary()])]);
    const { user } = renderApp();

    expect(await screen.findByText('2 itineraries loaded')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('3 itineraries loaded')).toBeInTheDocument();
    expect(screen.getByText('End of results')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
    expect(screen.queryByText(/of \d+ results|page \d+/i)).not.toBeInTheDocument();
  });

  it('AC-13: an empty first page with hasMore is not the end: the next page is fetched automatically', async () => {
    servePages([page([], 'c1'), page([itinerary()])]);

    renderApp();

    await waitFor(() => expect(resultRows()).toHaveLength(1));
    expect(screen.queryByText('No itineraries match these filters')).not.toBeInTheDocument();
    expect(screen.getByText('End of results')).toBeInTheDocument();
  });

  it('AC-13: after "Load more" returns an empty page with hasMore, paging continues automatically', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    servePages([page([itinerary()], 'c1'), page([], 'c2'), page([], 'c3'), page([itinerary()])]);
    const { user } = renderApp();
    await waitFor(() => expect(resultRows()).toHaveLength(1));

    await user.click(screen.getByRole('button', { name: 'Load more' }));

    await waitFor(() => expect(resultRows()).toHaveLength(2));
    expect(urls.map((url) => url.searchParams.get('cursor'))).toEqual([null, 'c1', 'c2', 'c3']);
  });

  it('AC-13: a short page with hasMore keeps the "Load more" button', async () => {
    servePages([page([itinerary()], 'c1'), page([itinerary()])]);

    renderApp();

    await waitFor(() => expect(resultRows()).toHaveLength(1));
    expect(screen.getByRole('button', { name: 'Load more' })).toBeInTheDocument();
  });

  it('AC-13: after 5 consecutive empty pages it stops and shows "Load more" again', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    servePages([
      page([itinerary()], 'c1'),
      ...Array.from({ length: 7 }, (_, i) => page([], `c${i + 2}`)),
    ]);
    const { user } = renderApp();
    await waitFor(() => expect(resultRows()).toHaveLength(1));

    await user.click(screen.getByRole('button', { name: 'Load more' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Load more' })).toBeEnabled());
    expect(urls).toHaveLength(1 + 5);
    expect(resultRows()).toHaveLength(1);
  });

  it('AC-14: a failed page keeps the loaded items, shows the problem and offers "Restart search"', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    server.use(
      http.get('/api/v1/itineraries', ({ request }) =>
        new URL(request.url).searchParams.has('cursor')
          ? problemResponse(problem(400, 'Invalid request', 'cursor was issued for other filters'))
          : HttpResponse.json(page([itinerary(), itinerary()], 'c1')),
      ),
    );
    const { user } = renderApp();
    await waitFor(() => expect(resultRows()).toHaveLength(2));

    await user.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'cursor was issued for other filters',
    );
    expect(resultRows()).toHaveLength(2);

    await user.click(screen.getByRole('button', { name: 'Restart search' }));

    await waitFor(() => expect(urls).toHaveLength(3));
    expect(urls[2]?.searchParams.has('cursor')).toBe(false);
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });
});
