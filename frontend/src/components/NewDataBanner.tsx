// "New data available" (AC-18): offered, never applied automatically, so a user who is paging is not interrupted.
// The live region is always rendered, so screen readers announce the banner when it appears (AC-21).
export function NewDataBanner({ visible, onReload }: { visible: boolean; onReload: () => void }) {
  return (
    <div className="new-data" data-testid="new-data-region" aria-live="polite">
      {visible && (
        <div className="banner">
          <strong>New data available</strong>
          <span> — a newer ingestion run has published data.</span>
          <button type="button" onClick={onReload}>
            Reload results
          </button>
        </div>
      )}
    </div>
  );
}
