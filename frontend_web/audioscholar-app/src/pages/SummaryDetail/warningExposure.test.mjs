import test from 'node:test';
import assert from 'node:assert/strict';

import { emitExpandedWarningReceipts, emitVisibleWarningReceipts } from './warningExposure.js';

test('viewport receipt is emitted only at fifty-percent visibility and only once', () => {
  const sent = new Set();
  const receipts = [];
  const warnings = [{ warningId: 'w1' }];
  const send = (warningId, state) => receipts.push({ warningId, state });

  assert.equal(emitVisibleWarningReceipts({ isIntersecting: true, intersectionRatio: 0.49 }, warnings, sent, send),
    false);
  assert.deepEqual(receipts, []);
  assert.equal(emitVisibleWarningReceipts({ isIntersecting: true, intersectionRatio: 0.5 }, warnings, sent, send),
    true);
  emitVisibleWarningReceipts({ isIntersecting: true, intersectionRatio: 1 }, warnings, sent, send);
  assert.deepEqual(receipts, [{ warningId: 'w1', state: 'INDICATOR_VISIBLE' }]);
});

test('expansion receipt is emitted when details open and only once', () => {
  const sent = new Set();
  const receipts = [];
  const send = (warningId, state) => receipts.push({ warningId, state });

  emitExpandedWarningReceipts([{ warningId: 'w1' }], sent, send);
  emitExpandedWarningReceipts([{ warningId: 'w1' }], sent, send);

  assert.deepEqual(receipts, [{ warningId: 'w1', state: 'DETAILS_EXPANDED' }]);
});
