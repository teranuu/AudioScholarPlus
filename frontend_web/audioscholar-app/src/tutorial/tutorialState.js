export const tutorialSteps = [
  'Upload one lecture',
  'Wait for processing',
  'Review audio quality',
  'Review output and warnings',
  'Upload three related sources',
  'Review merged output',
];

export const samples = [
  { name: 'Source 1 · Sample lecture audio', id: '1GUKm0ZS6OOS7nCpnvYuvELQxy8a-Cbhm' },
  { name: 'Source 2 · Related lecture audio or video', id: '11_yO7oYKPzgUKppSD7uGNgYlbTbcPq8_' },
  { name: 'Source 3 · Lecture slides', id: '1GStYJmNxSGZ-WHizTF02DxV9MXlztx7X' },
];

export const initialTutorial = { enabled: false, dismissed: false, step: 0, recordingId: null, jobId: null };

export function tutorialKey(userId) {
  return `audioscholar_tutorial_v1_${userId}`;
}

export function readTutorial(userId) {
  if (!userId) return initialTutorial;
  try {
    const saved = JSON.parse(localStorage.getItem(tutorialKey(userId)));
    if (saved && Number.isInteger(saved.step) && saved.step >= 0 && saved.step <= tutorialSteps.length) {
      return { ...initialTutorial, ...saved };
    }
  } catch {
    // A damaged browser entry should not break the application.
  }
  return initialTutorial;
}

export function advanceTutorial(state, event) {
  if (!state.enabled) return state;
  if (event.type === 'singleSubmitted' && state.step === 0 && event.recordingId) {
    return { ...state, step: 1, recordingId: event.recordingId };
  }
  if (event.type === 'singleReady' && state.step === 1 && event.recordingId === state.recordingId) {
    return { ...state, step: 2 };
  }
  if (event.type === 'qualityReviewed' && state.step === 2 && event.recordingId === state.recordingId) {
    return { ...state, step: 3 };
  }
  if (event.type === 'outputReviewed' && state.step === 3 && event.recordingId === state.recordingId) {
    return { ...state, step: 4 };
  }
  if (event.type === 'multiSubmitted' && state.step === 4 && event.jobId && event.sourceCount === 3) {
    return { ...state, step: 5, jobId: event.jobId };
  }
  if (event.type === 'mergedReviewed' && state.step === 5 && event.jobId === state.jobId && event.status === 'COMPLETE') {
    return { ...state, step: 6 };
  }
  return state;
}
