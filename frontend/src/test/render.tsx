import { StrictMode } from 'react';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import App from '../App';
import type { AppConfig } from '../config';

/**
 * Renders the whole dashboard, as the user sees it, with a user-event instance to drive it. In StrictMode, like
 * main.tsx: React then mounts every effect twice in development, which flushes out missing cleanups.
 */
export function renderApp(config: Partial<AppConfig> = {}) {
  // With fake timers (polling tests), user-event must advance them while it waits between key strokes.
  const user = userEvent.setup(vi.isFakeTimers() ? { advanceTimers: vi.advanceTimersByTime } : {});
  const view = render(
    <StrictMode>
      <App config={{ demoControls: false, ...config }} />
    </StrictMode>,
  );
  return { user, ...view };
}

/** The result rows currently displayed (one list item per itinerary). */
export function resultRows() {
  const list = screen.queryByRole('list', { name: 'Itineraries' });
  return list ? within(list).queryAllByRole('listitem', { name: /→/ }) : [];
}

/** Waits until the catalog has filled the search form (its controls become enabled). */
export async function catalogLoaded() {
  const origin = await screen.findByLabelText('Origin');
  await vi.waitFor(() => expect(origin).toBeEnabled());
}
