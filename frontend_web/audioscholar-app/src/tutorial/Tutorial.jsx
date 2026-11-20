import { createContext, useContext, useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { advanceTutorial, initialTutorial, readTutorial, samples, tutorialKey, tutorialSteps } from './tutorialState';

const TutorialContext = createContext(null);

export function TutorialProvider({ children }) {
  const location = useLocation();
  const userId = localStorage.getItem('AuthToken') && localStorage.getItem('userId');
  const [account, setAccount] = useState(userId);
  const [state, setState] = useState(() => readTutorial(userId));

  useEffect(() => {
    if (account !== userId) {
      setAccount(userId);
      setState(readTutorial(userId));
    }
  }, [account, userId, location.pathname]);

  const update = (change) => {
    if (!userId || account !== userId) return;
    setState((current) => {
      const next = typeof change === 'function' ? change(current) : change;
      localStorage.setItem(tutorialKey(userId), JSON.stringify(next));
      return next;
    });
  };

  const value = {
    state: account === userId ? state : initialTutorial,
    start: () => update({ ...initialTutorial, enabled: true }),
    resume: () => update((current) => ({ ...current, enabled: true })),
    disable: () => update((current) => ({ ...current, enabled: false, dismissed: true })),
    dismiss: () => update((current) => ({ ...current, dismissed: true })),
    retryMulti: () => update((current) => ({ ...current, step: 4, jobId: null })),
    advance: (event) => update((current) => advanceTutorial(current, event)),
  };
  return <TutorialContext.Provider value={value}>{children}</TutorialContext.Provider>;
}

export function useTutorial() {
  return useContext(TutorialContext);
}

function SampleLinks({ sourceIndexes }) {
  return <ul className="mt-3 space-y-2 text-sm">
    {sourceIndexes.map((index) => {
      const sample = samples[index];
      return <li key={sample.id} className="flex flex-wrap items-center gap-2">
        <span>{sample.name}</span>
        <a className="text-teal-700 underline" href={`https://drive.google.com/uc?export=download&id=${sample.id}`} target="_blank" rel="noopener noreferrer">Download</a>
        <a className="text-teal-700 underline" href={`https://drive.google.com/file/d/${sample.id}/view`} target="_blank" rel="noopener noreferrer">Open in Drive</a>
      </li>;
    })}
  </ul>;
}

export function TutorialPanel({ detail, action, actionLabel, children }) {
  const { state, disable } = useTutorial();
  if (!state.enabled) return null;
  const currentStep = tutorialSteps[state.step];
  return <section aria-label="Guided tutorial" className="mb-6 rounded-xl border border-teal-300 bg-teal-50 p-4 text-gray-900 shadow-sm dark:border-teal-700 dark:bg-gray-800 dark:text-gray-100">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div>
        <p className="text-sm text-teal-800 dark:text-teal-300">Guided tutorial · {state.step < 6 ? `Step ${state.step + 1} of 6` : 'Complete'}</p>
        <h2 className="text-lg font-semibold" tabIndex="-1">{currentStep || 'Both tasks complete'}</h2>
      </div>
      <button type="button" onClick={disable} className="text-sm underline">Turn off tutorial</button>
    </div>
    {state.step === 6 ? <p className="mt-2">You’ve completed both tasks. Please return to the research team for the next step.</p> : <>
      {detail && <p className="mt-2 text-sm">{detail}</p>}
      {(state.step === 0 || state.step === 4) && <SampleLinks sourceIndexes={state.step === 0 ? [0] : [0, 1, 2]} />}
      {state.step === 1 && <p className="mt-2 text-sm">Leave the page open while processing. Avoid refreshing, cancelling, or submitting the same recording again.</p>}
      {state.step === 5 && <p className="mt-2 text-sm">Wait until all three sources finish. Read the full merged output, key points, source labels, evidence, timestamps, and references for Sources 1–3.</p>}
      {children}
      {action && <div className="mt-3">{typeof action === 'string' ? <Link className="inline-block rounded bg-teal-700 px-4 py-2 text-white" to={action}>{actionLabel}</Link> : <button type="button" className="rounded bg-teal-700 px-4 py-2 text-white disabled:opacity-50" onClick={action} disabled={!actionLabel}>{actionLabel}</button>}</div>}
      <details className="mt-3 text-sm md:hidden"><summary className="cursor-pointer">Show all steps</summary><ol className="mt-2 list-decimal pl-5">{tutorialSteps.map((step, index) => <li key={step} aria-current={index === state.step ? 'step' : undefined}>{step}</li>)}</ol></details>
      <ol className="mt-3 hidden flex-wrap gap-x-5 gap-y-1 text-xs md:flex">{tutorialSteps.map((step, index) => <li key={step} className={index === state.step ? 'font-bold text-teal-800' : ''} aria-current={index === state.step ? 'step' : undefined}>{index + 1}. {step}</li>)}</ol>
    </>}
  </section>;
}

export function TutorialDashboard() {
  const { state, start, resume, dismiss } = useTutorial();
  if (state.enabled) {
    const destinations = ['/upload', state.recordingId ? `/recordings/${state.recordingId}` : '/recordings', state.recordingId ? `/recordings/${state.recordingId}` : '/recordings', state.recordingId ? `/recordings/${state.recordingId}` : '/recordings', '/upload/multi-source', '/upload/multi-source'];
    return <TutorialPanel detail="Follow the current step, then return here whenever you need to resume." action={state.step < 6 ? destinations[state.step] : null} actionLabel="Continue tutorial" />;
  }
  if (state.step > 0 && state.step < 6) return <section className="mb-6 rounded-xl bg-white p-5 shadow dark:bg-gray-800 dark:text-white"><p>Guided tutorial paused at step {state.step + 1}.</p><button className="mt-3 rounded bg-teal-700 px-4 py-2 text-white" onClick={resume}>Resume tutorial</button></section>;
  if (state.step === 6) return <section className="mb-6 rounded-xl bg-white p-5 shadow dark:bg-gray-800 dark:text-white"><p>You’ve completed both tasks. Please return to the research team for the next step.</p><button className="mt-3 rounded bg-teal-700 px-4 py-2 text-white" onClick={start}>Restart tutorial</button></section>;
  if (state.dismissed) return <button className="mb-6 rounded bg-teal-700 px-4 py-2 text-white" onClick={start}>Start guided tutorial</button>;
  return <section className="mb-6 rounded-xl border border-teal-200 bg-teal-50 p-5"><h2 className="text-lg font-semibold">Try the guided tutorial</h2><p className="mt-1">Use a sample lecture to review audio quality and generated notes, then combine three related sources into one set of notes. About 20–30 minutes.</p><div className="mt-3 flex gap-3"><button className="rounded bg-teal-700 px-4 py-2 text-white" onClick={start}>Start tutorial</button><button className="underline" onClick={dismiss}>Maybe later</button></div></section>;
}
