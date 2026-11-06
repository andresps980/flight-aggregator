import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { itinerary, page } from './test/fixtures';
import { catalogLoaded, renderApp, resultRows } from './test/render';
import { recordRequests, server } from './test/server';

describe('accessibility (AC-21)', () => {
  it('AC-21: every form control has a visible label', async () => {
    renderApp();
    await catalogLoaded();

    for (const name of [
      'Origin',
      'Destination',
      'Departure date (UTC)',
      'Max price (EUR)',
      'Carrier',
    ]) {
      const control = screen.getByLabelText(name);
      const label = document.querySelector(`label[for="${control.id}"]`);
      expect(label, name).not.toBeNull();
      expect(label).toBeVisible();
    }
  });

  it('AC-21: the search form is operable with the keyboard alone, in a logical order', async () => {
    const urls = recordRequests('/api/v1/itineraries');
    const { user } = renderApp();
    await catalogLoaded();
    await waitFor(() => expect(urls).toHaveLength(1));
    // A native <select> changes with the arrow keys in a real browser; user-event does not simulate that, so the
    // value is picked with selectOptions and the test checks focus order and keyboard submission.
    await user.selectOptions(screen.getByLabelText('Origin'), 'BCN');
    screen.getByLabelText('Origin').focus();

    await user.tab();
    expect(screen.getByLabelText('Destination')).toHaveFocus();
    await user.tab();
    expect(screen.getByLabelText('Departure date (UTC)')).toHaveFocus();
    await user.tab();
    expect(screen.getByLabelText('Max price (EUR)')).toHaveFocus();
    await user.tab();
    expect(screen.getByLabelText('Carrier')).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Search' })).toHaveFocus();
    await user.keyboard('{Enter}');

    await waitFor(() => expect(urls).toHaveLength(2));
    expect(urls[1]?.searchParams.get('origin')).toBe('BCN');
  });

  it('AC-21: the result count, banner and refresh outcomes are announced through live regions', async () => {
    server.use(http.get('/api/v1/itineraries', () => HttpResponse.json(page([itinerary()]))));
    renderApp();
    await waitFor(() => expect(resultRows()).toHaveLength(1));

    const count = screen.getByText('1 itinerary loaded');
    expect(count.closest('[aria-live]')).toHaveAttribute('aria-live', 'polite');
    const refreshStatus = screen.getByTestId('refresh-status');
    expect(refreshStatus).toHaveAttribute('aria-live', 'polite');
    const bannerRegion = screen.getByTestId('new-data-region');
    expect(bannerRegion).toHaveAttribute('aria-live', 'polite');
  });

  it('AC-21: the page has one main landmark and a top-level heading', async () => {
    renderApp();

    expect(await screen.findByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Flight Aggregator');
  });
});
