// Shows a failed request readably: "Title — detail" for RFC 9457 problems, a generic message otherwise (AC-20).
import type { ReactNode } from 'react';
import type { ApiError } from '../api/client';

export function ProblemAlert({ error, children }: { error: ApiError; children?: ReactNode }) {
  return (
    <div role="alert" className="alert">
      <p>{error.message}</p>
      {children}
    </div>
  );
}
