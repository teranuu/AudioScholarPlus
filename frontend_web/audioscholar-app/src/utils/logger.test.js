import { afterEach, describe, expect, it, vi } from 'vitest';

import { initLogger } from './logger';

const originalConsole = { ...console };

describe('initLogger', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    console.log = originalConsole.log;
    console.debug = originalConsole.debug;
    console.info = originalConsole.info;
  });

  it('suppresses verbose console methods outside localhost', () => {
    vi.stubGlobal('window', { location: { hostname: 'audioscholar.vercel.app' } });

    initLogger();

    expect(console.log).not.toBe(originalConsole.log);
    expect(console.debug).not.toBe(originalConsole.debug);
    expect(console.info).not.toBe(originalConsole.info);
  });
});
