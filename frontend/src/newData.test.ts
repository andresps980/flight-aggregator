import { describe, expect, it } from 'vitest';
import { baselineOf, isNewDataAvailable } from './newData';
import { run } from './test/fixtures';

describe('new data rule (AC-18, Q5)', () => {
    it('AC-18: the baseline remembers the run seen when the search started and whether it had finished', () => {
        expect(baselineOf(null)).toBeNull();
        expect(baselineOf(run({ id: 'a' }))).toEqual({ runId: 'a', finished: true });
        expect(baselineOf(run({ id: 'b', status: 'RUNNING' }))).toEqual({
            runId: 'b',
            finished: false,
        });
    });

    it.each([
        ['no run seen, now COMPLETED', null, run({ id: 'a' }), true],
        ['no run seen, now PARTIAL', null, run({ id: 'a', status: 'PARTIAL' }), true],
        ['same finished run', { runId: 'a', finished: true }, run({ id: 'a' }), false],
        ['newer COMPLETED run', { runId: 'a', finished: true }, run({ id: 'b' }), true],
        [
            'newer PARTIAL run',
            { runId: 'a', finished: true },
            run({ id: 'b', status: 'PARTIAL' }),
            true,
        ],
        [
            'newer FAILED run (warned in the panel instead)',
            { runId: 'a', finished: true },
            run({ id: 'b', status: 'FAILED' }),
            false,
        ],
        [
            'newer ABORTED run',
            { runId: 'a', finished: true },
            run({ id: 'b', status: 'ABORTED' }),
            false,
        ],
        [
            'newer run still RUNNING',
            { runId: 'a', finished: true },
            run({ id: 'b', status: 'RUNNING' }),
            false,
        ],
        [
            'baseline run was RUNNING and has now COMPLETED',
            { runId: 'a', finished: false },
            run({ id: 'a' }),
            true,
        ],
        [
            'baseline run was RUNNING and is still RUNNING',
            { runId: 'a', finished: false },
            run({ id: 'a', status: 'RUNNING' }),
            false,
        ],
        ['no latest run (404)', { runId: 'a', finished: true }, null, false],
    ] as const)('AC-18: %s', (_, baseline, latest, expected) => {
        expect(isNewDataAvailable(baseline, latest)).toBe(expected);
    });
});
