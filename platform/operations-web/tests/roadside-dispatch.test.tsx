import { describe, expect, it } from 'vitest';

import { nextSteps } from '../app/roadside-dispatch';

describe('nextSteps', () => {
  it('walks a confirmed tow from the road to completion', () => {
    expect(nextSteps('EN_ROUTE').map((step) => step.status)).toEqual(['ON_SCENE']);
    expect(nextSteps('ON_SCENE').map((step) => step.status)).toEqual(['TOWING', 'COMPLETED']);
    expect(nextSteps('TOWING').map((step) => step.status)).toEqual(['COMPLETED']);
  });

  it('offers no progress before a truck is confirmed or after the request closes', () => {
    for (const status of ['OPEN', 'QUOTED', 'SEARCHING', 'COMPLETED', 'CANCELLED', 'EXPIRED']) {
      expect(nextSteps(status)).toEqual([]);
    }
  });
});
