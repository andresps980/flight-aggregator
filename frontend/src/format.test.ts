import { describe, expect, it } from 'vitest';
import { dayOffset, formatDuration, formatEur, formatUtc, markup, stopsLabel } from './format';

describe('format', () => {
  it('AC-8: formats an instant as UTC "dd Mon HH:mm", whatever the machine time zone', () => {
    expect(formatUtc('2026-11-08T10:15:00Z')).toBe('08 Nov 10:15');
    expect(formatUtc('2026-11-08T23:59:00Z')).toBe('08 Nov 23:59');
    expect(formatUtc('2026-12-31T00:05:00Z')).toBe('31 Dec 00:05');
  });

  it('AC-8: counts how many UTC days later an arrival is', () => {
    expect(dayOffset('2026-11-08T10:15:00Z', '2026-11-08T18:40:00Z')).toBe(0);
    expect(dayOffset('2026-11-08T20:00:00Z', '2026-11-09T06:05:00Z')).toBe(1);
    expect(dayOffset('2026-11-08T23:30:00Z', '2026-11-10T00:10:00Z')).toBe(2);
  });

  it('AC-7: formats the total duration in hours and minutes', () => {
    expect(formatDuration('2026-11-08T10:15:00Z', '2026-11-08T18:40:00Z')).toBe('8h 25m');
    expect(formatDuration('2026-11-08T10:00:00Z', '2026-11-08T10:45:00Z')).toBe('0h 45m');
    expect(formatDuration('2026-11-08T20:00:00Z', '2026-11-09T06:05:00Z')).toBe('10h 05m');
  });

  it('AC-7: formats EUR amounts with two decimals', () => {
    expect(formatEur(433)).toBe('€433.00');
    expect(formatEur(1234.5)).toBe('€1,234.50');
    expect(formatEur(0.1)).toBe('€0.10');
  });

  it('AC-7: computes the markup in cents, without floating-point drift', () => {
    expect(markup(412.38, 433.0)).toBe(20.62);
    expect(markup(0.1, 0.3)).toBe(0.2); // 0.3 - 0.1 is 0.19999999999999998 in floating point
    expect(markup(250, 262.5)).toBe(12.5);
  });

  it('AC-7: labels the number of stops', () => {
    expect(stopsLabel(0)).toBe('Direct');
    expect(stopsLabel(1)).toBe('1 stop');
    expect(stopsLabel(2)).toBe('2 stops');
  });
});
