import test from 'node:test';
import assert from 'node:assert/strict';
import { advanceTutorial, initialTutorial, readTutorial, tutorialKey } from './tutorialState.js';

test('the guided sequence follows only its submitted recording and job', () => {
  let state = { ...initialTutorial, enabled: true };
  state = advanceTutorial(state, { type: 'singleSubmitted', recordingId: 'recording-1' });
  assert.equal(state.step, 1);
  assert.equal(advanceTutorial(state, { type: 'singleReady', recordingId: 'other' }).step, 1);
  state = advanceTutorial(state, { type: 'singleReady', recordingId: 'recording-1' });
  state = advanceTutorial(state, { type: 'qualityReviewed', recordingId: 'recording-1' });
  state = advanceTutorial(state, { type: 'outputReviewed', recordingId: 'recording-1' });
  assert.equal(advanceTutorial(state, { type: 'multiSubmitted', jobId: 'job-1', sourceCount: 2 }).step, 4);
  state = advanceTutorial(state, { type: 'multiSubmitted', jobId: 'job-1', sourceCount: 3 });
  assert.equal(advanceTutorial(state, { type: 'mergedReviewed', jobId: 'job-1', status: 'INCOMPLETE' }).step, 5);
  assert.equal(advanceTutorial(state, { type: 'mergedReviewed', jobId: 'other', status: 'COMPLETE' }).step, 5);
  assert.equal(advanceTutorial(state, { type: 'mergedReviewed', jobId: 'job-1', status: 'COMPLETE' }).step, 6);
});

test('saved progress is isolated per account and ignores damaged entries', () => {
  const entries = new Map();
  globalThis.localStorage = {
    getItem: (key) => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, value),
  };
  entries.set(tutorialKey('user-1'), JSON.stringify({ enabled: true, step: 3, recordingId: 'recording-1' }));
  entries.set(tutorialKey('user-2'), '{broken');
  assert.equal(readTutorial('user-1').step, 3);
  assert.equal(readTutorial('user-2').step, 0);
  assert.equal(readTutorial(null).enabled, false);
});
