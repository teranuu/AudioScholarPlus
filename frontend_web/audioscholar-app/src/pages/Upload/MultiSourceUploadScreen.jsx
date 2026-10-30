import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { FiCheckCircle, FiClock, FiFile, FiFileText, FiLoader, FiUpload, FiXCircle } from 'react-icons/fi';
import { API_BASE_URL } from '../../services/authService';
import { Header } from '../Home/HomePage';
import ReactMarkdown from 'react-markdown';

const OUTPUT_TYPES = [
  { value: 'NOTES', title: 'Notes', description: 'Detailed lecture notes from every source.' },
  { value: 'STUDY_MATERIAL', title: 'Study Material', description: 'A unified study guide with definitions and structure.' },
  { value: 'REVIEW_MATERIAL', title: 'Review Material', description: 'A deduplicated flashcard deck for quick recall.' },
];

const VALID_MEDIA_TYPES = [
  'audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/x-wav', 'audio/aac', 'audio/x-aac',
  'audio/ogg', 'application/ogg', 'audio/flac', 'audio/x-flac', 'audio/aiff', 'audio/x-aiff',
  'audio/mp4', 'audio/m4a', 'video/mp4', 'video/webm', 'video/quicktime',
];
const VALID_DOCUMENT_TYPES = [
  'application/pdf',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.ms-powerpoint',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
];
const VALID_MEDIA_EXTENSIONS = ['mp3', 'wav', 'aac', 'ogg', 'flac', 'aiff', 'm4a', 'mp4', 'webm', 'mov'];
const VALID_DOCUMENT_EXTENSIONS = ['pdf', 'ppt', 'pptx', 'doc', 'docx'];
const MEDIA_ACCEPT = VALID_MEDIA_EXTENSIONS.map((ext) => `.${ext}`).join(',');
const DOCUMENT_ACCEPT = VALID_DOCUMENT_EXTENSIONS.map((ext) => `.${ext}`).join(',');
const MEDIA_LABEL = VALID_MEDIA_EXTENSIONS.join(', ').toUpperCase();
const DOCUMENT_LABEL = VALID_DOCUMENT_EXTENSIONS.join(', ').toUpperCase();
const MAX_TOTAL_SOURCES = 5;
const JOB_POLL_INITIAL_DELAY_MS = 2000;
const JOB_POLL_MAX_DELAY_MS = 15000;
const JOB_POLL_MAX_DURATION_MS = 30 * 60 * 1000;
const ACTIVE_JOB_STORAGE_KEY = 'AudioScholar.activeMultiSourceJobId';
const SUBMISSION_KEY_STORAGE_KEY = 'AudioScholar.multiSourceSubmissionKey';
const TERMINAL_JOB_STATUSES = new Set(['COMPLETE', 'FAILED']);

const sourceLabel = (index) => `Source ${String.fromCharCode(65 + index)}`;
const extensionOf = (file) => file.name.split('.').pop().toLowerCase();
const isValidMediaFile = (file) => {
  const ext = extensionOf(file);
  return VALID_MEDIA_TYPES.includes((file.type || '').toLowerCase()) || VALID_MEDIA_EXTENSIONS.includes(ext);
};
const isValidDocumentFile = (file) => {
  const ext = extensionOf(file);
  return VALID_DOCUMENT_TYPES.includes((file.type || '').toLowerCase()) || VALID_DOCUMENT_EXTENSIONS.includes(ext);
};
const withoutExtension = (fileName) => fileName.replace(/\.[^/.]+$/, '');
const formatPhilippineDateTime = (value) => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return 'the next quota window';
  return new Intl.DateTimeFormat('en-PH', {
    timeZone: 'Asia/Manila',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    timeZoneName: 'short',
  }).format(date);
};

const SourceFileList = ({ files, kind, offset = 0, loading, onRemove }) => (
  <div className="space-y-2 mt-3">
    {files.map((file, index) => (
      <div key={`${kind}-${file.name}-${index}`} className="flex items-center justify-between border border-gray-200 dark:border-gray-700 rounded-lg p-3">
        <div className="flex items-center min-w-0">
          <span className="shrink-0 text-xs font-semibold bg-teal-100 text-teal-800 px-2 py-1 rounded mr-3">{sourceLabel(offset + index)}</span>
          {kind === 'document' ? (
            <FiFileText className="shrink-0 h-5 w-5 text-blue-500 mr-2" />
          ) : (
            <FiFile className="shrink-0 h-5 w-5 text-teal-500 mr-2" />
          )}
          <span className="truncate text-sm text-gray-800 dark:text-gray-100">{file.name}</span>
        </div>
        <button type="button" onClick={() => onRemove(index)} disabled={loading} className="text-red-600 hover:text-red-800 p-1" title="Remove source">
          <FiXCircle />
        </button>
      </div>
    ))}
  </div>
);

const MultiSourceFilePicker = ({ label, supportedLabel, disabled, onPick, children }) => (
  <div
    onClick={onPick}
    className={`border-2 border-dashed border-gray-300 dark:border-gray-600 rounded-lg p-6 text-center transition-colors duration-200 ${disabled ? 'opacity-60 cursor-not-allowed bg-gray-100 dark:bg-gray-700' : 'cursor-pointer hover:bg-gray-100 dark:hover:bg-gray-700 hover:border-teal-400 dark:hover:border-teal-400'}`}
  >
    <FiUpload className="mx-auto h-10 w-10 text-gray-400 dark:text-gray-500" />
    <p className="text-gray-600 dark:text-gray-300 mt-2 mb-1 text-sm">{label}</p>
    <p className="text-xs text-gray-500 dark:text-gray-400">Supports: {supportedLabel}</p>
    {children}
  </div>
);

const UploadProgressIndicator = ({ loading, job }) => {
  if (!loading) return null;
  const accepted = Boolean(job?.jobId);
  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 z-50 flex flex-col items-center justify-center">
      <FiLoader className="animate-spin h-10 w-10 text-white mb-3" />
      <p className="text-white text-lg font-medium">{accepted ? 'Processing sources in the background...' : 'Uploading sources...'}</p>
      <p className="text-gray-300 text-sm mt-1">
        {accepted ? 'The job is saved. You may leave this page and check it later.' : 'Keep this page open until the upload is accepted.'}
      </p>
    </div>
  );
};

const SourceAttributionChip = ({ source }) => (
  <span className="text-xs bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-200 rounded px-2 py-1">
    {source.sourceLabel}: {source.fileName}
  </span>
);

const MergeStatusIndicator = ({ job }) => {
  if (!job || job.status !== 'COMPLETE' || !job.mergedSummary) return null;
  const degraded = job.semanticEnrichmentStatus === 'DEGRADED';
  return (
    <div className="text-center">
      <p className="text-sm text-green-600 dark:text-green-400 flex items-center justify-center gap-1"><FiCheckCircle /> Merged summary generated.</p>
      {degraded && <p className="text-xs text-amber-700 dark:text-amber-300 mt-1">Source attribution is incomplete, but the merged summary is available.</p>}
    </div>
  );
};

const DeferredStatusIndicator = ({ job }) => {
  if (!job || job.status !== 'PROCESSING_QUEUED' || !job.quotaRetryAt) return null;
	const retryLabel = formatPhilippineDateTime(job.quotaRetryAt);
  return (
    <div className="text-sm text-amber-700 dark:text-amber-300 text-center flex items-center justify-center gap-1">
      <FiClock /> Processing is paused for the daily Gemini limit and will resume automatically at {retryLabel}.
    </div>
  );
};

const MergedSummaryContentViewer = ({ summary }) => (
  <div className="prose prose-sm max-w-none dark:prose-invert">
    <ReactMarkdown>{summary?.formattedSummaryText || 'Merged summary is available.'}</ReactMarkdown>
  </div>
);

const MergedSummaryScreen = ({ job }) => {
  if (!job || job.status !== 'COMPLETE' || !job.mergedSummary) return null;
  return (
    <div className="mt-8 border-t border-gray-200 dark:border-gray-700 pt-6">
      <div className="flex flex-wrap gap-2 mb-4">
        {(job.sourceFiles || []).map((source) => (
          <SourceAttributionChip key={source.sourceFileId} source={source} />
        ))}
      </div>
      <MergedSummaryContentViewer summary={job.mergedSummary} />
    </div>
  );
};

const MultiSourceUploadScreen = () => {
  const [mediaFiles, setMediaFiles] = useState([]);
  const [documentFiles, setDocumentFiles] = useState([]);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [outputType, setOutputType] = useState('');
  const [mediaError, setMediaError] = useState('');
  const [documentError, setDocumentError] = useState('');
  const [formError, setFormError] = useState('');
  const [loading, setLoading] = useState(false);
  const [polling, setPolling] = useState(false);
  const [job, setJob] = useState(null);
  const [recentJobs, setRecentJobs] = useState([]);
  const mediaInputRef = useRef(null);
  const documentInputRef = useRef(null);
  const pollAbortRef = useRef(null);
  const navigate = useNavigate();

  const sourceCount = mediaFiles.length + documentFiles.length;
  const remainingSlots = MAX_TOTAL_SOURCES - sourceCount;
  const hasActiveJob = Boolean(job?.jobId && !TERMINAL_JOB_STATUSES.has(job.status));

  const handleUnauthorized = useCallback(() => {
    localStorage.removeItem('AuthToken');
    localStorage.removeItem('userId');
    navigate('/signin');
  }, [navigate]);

  const mergeRecentJob = useCallback((updatedJob) => {
    if (!updatedJob?.jobId) return;
    setRecentJobs((current) => [updatedJob, ...current.filter((item) => item.jobId !== updatedJob.jobId)].slice(0, 10));
  }, []);

  const pollJob = useCallback(async (jobId, token) => {
    pollAbortRef.current?.abort();
    const controller = new AbortController();
    pollAbortRef.current = controller;
    setPolling(true);
    let pollDelayMs = 0;
    let transientFailures = 0;
    const pollingStartedAt = Date.now();
    try {
      while (!controller.signal.aborted) {
        if (pollDelayMs > 0) {
          await new Promise((resolve, reject) => {
            const timeout = window.setTimeout(resolve, pollDelayMs);
            controller.signal.addEventListener('abort', () => {
              window.clearTimeout(timeout);
              reject(new DOMException('Polling cancelled', 'AbortError'));
            }, { once: true });
          });
        }
        let response;
        try {
          response = await fetch(`${API_BASE_URL}api/audio/multi-source/${jobId}`, {
            headers: { Authorization: `Bearer ${token}`, 'X-AudioScholar-Client': 'WEB' },
            signal: controller.signal,
          });
        } catch (error) {
          if (error.name === 'AbortError') throw error;
          transientFailures += 1;
          if (transientFailures > 3) throw new Error('The status service is temporarily unavailable. The job will continue in the background.');
          pollDelayMs = Math.min(Math.max(JOB_POLL_INITIAL_DELAY_MS, pollDelayMs * 2), JOB_POLL_MAX_DELAY_MS);
          continue;
        }
        if (response.status === 401) {
          handleUnauthorized();
          return;
        }
        if (!response.ok) {
          transientFailures += 1;
          if (transientFailures > 3) throw new Error(`Could not check processing status (${response.status}). The job will continue in the background.`);
          pollDelayMs = Math.min(Math.max(JOB_POLL_INITIAL_DELAY_MS, pollDelayMs * 2), JOB_POLL_MAX_DELAY_MS);
          continue;
        }
        transientFailures = 0;
        const current = await response.json();
        setJob(current);
        mergeRecentJob(current);
        if (TERMINAL_JOB_STATUSES.has(current.status)) {
          localStorage.removeItem(ACTIVE_JOB_STORAGE_KEY);
          if (current.status === 'FAILED') setFormError(current.failureReason || 'Multi-source processing failed.');
          return;
        }
        if (current.status === 'PROCESSING_QUEUED' && current.quotaRetryAt) return;
        if (Date.now() - pollingStartedAt >= JOB_POLL_MAX_DURATION_MS) {
          setFormError('Processing is taking longer than expected. It is still running in the background and can be resumed from Recent jobs.');
          return;
        }
        pollDelayMs = Math.min(Math.ceil(Math.max(JOB_POLL_INITIAL_DELAY_MS, pollDelayMs) * 1.5), JOB_POLL_MAX_DELAY_MS);
      }
    } catch (error) {
      if (error.name !== 'AbortError') setFormError(error.message);
    } finally {
      if (pollAbortRef.current === controller) {
        pollAbortRef.current = null;
        setPolling(false);
      }
    }
  }, [handleUnauthorized, mergeRecentJob]);

  useEffect(() => {
    const token = localStorage.getItem('AuthToken');
    if (!token) return undefined;
    let cancelled = false;
    const restore = async () => {
      try {
        const response = await fetch(`${API_BASE_URL}api/audio/multi-source`, {
          headers: { Authorization: `Bearer ${token}`, 'X-AudioScholar-Client': 'WEB' },
        });
        if (response.status === 401) {
          handleUnauthorized();
          return;
        }
        if (response.ok) {
          const jobs = await response.json();
          if (!cancelled) setRecentJobs(jobs);
        }
      } catch {
        // Recovery is best-effort; a new upload remains available when the API is offline.
      }
      const activeJobId = localStorage.getItem(ACTIVE_JOB_STORAGE_KEY);
      if (activeJobId && !cancelled) await pollJob(activeJobId, token);
    };
    restore();
    return () => {
      cancelled = true;
      pollAbortRef.current?.abort();
    };
  }, [handleUnauthorized, pollJob]);

  useEffect(() => {
    const handleBeforeUnload = (event) => {
      event.preventDefault();
      event.returnValue = 'Upload in progress. Are you sure you want to leave?';
      return 'Upload in progress. Are you sure you want to leave?';
    };

    if (loading && !job?.jobId) {
      window.addEventListener('beforeunload', handleBeforeUnload);
    } else {
      window.removeEventListener('beforeunload', handleBeforeUnload);
    }

    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [loading, job?.jobId]);

  const addFiles = (event, kind) => {
    setFormError('');
    setJob(null);
    const selected = Array.from(event.target.files || []);
    const inputRef = kind === 'media' ? mediaInputRef : documentInputRef;
    const setError = kind === 'media' ? setMediaError : setDocumentError;
    const setFiles = kind === 'media' ? setMediaFiles : setDocumentFiles;
    const validator = kind === 'media' ? isValidMediaFile : isValidDocumentFile;
    const supported = kind === 'media' ? MEDIA_LABEL : DOCUMENT_LABEL;

    setError('');
    if (!selected.length) return;

    const invalid = selected.find((file) => !validator(file));
    if (invalid) {
      setError(`Unsupported ${kind} file: ${invalid.name}. Supported formats: ${supported}`);
      if (inputRef.current) inputRef.current.value = '';
      return;
    }
    if (selected.length > remainingSlots) {
      setError(`You can add ${remainingSlots} more source${remainingSlots === 1 ? '' : 's'} only.`);
      if (inputRef.current) inputRef.current.value = '';
      return;
    }

    setFiles((current) => {
      const next = [...current, ...selected];
      if (kind === 'media' && !title && next[0]) {
        setTitle(withoutExtension(next[0].name));
      }
      return next;
    });
    if (inputRef.current) inputRef.current.value = '';
  };

  const removeMediaFile = (index) => {
    setMediaFiles((current) => current.filter((_, itemIndex) => itemIndex !== index));
    setJob(null);
  };

  const removeDocumentFile = (index) => {
    setDocumentFiles((current) => current.filter((_, itemIndex) => itemIndex !== index));
    setJob(null);
  };

  const openRecentJob = (selectedJob) => {
    setFormError('');
    setJob(selectedJob);
    const token = localStorage.getItem('AuthToken');
    if (!token) {
      handleUnauthorized();
      return;
    }
    if (!TERMINAL_JOB_STATUSES.has(selectedJob.status))
      localStorage.setItem(ACTIVE_JOB_STORAGE_KEY, selectedJob.jobId);
    pollJob(selectedJob.jobId, token);
  };

  const submit = async (event) => {
    event.preventDefault();
    setMediaError('');
    setDocumentError('');
    setFormError('');
    setJob(null);

    if (sourceCount < 2) {
      setFormError('Select at least two sources.');
      return;
    }
    if (!title.trim()) {
      setFormError('Enter a title for the merged summary.');
      return;
    }
    if (!outputType) {
      setFormError('Select an output type.');
      return;
    }

    const token = localStorage.getItem('AuthToken');
    if (!token) {
      setFormError('Please sign in again before uploading.');
      navigate('/signin');
      return;
    }

    const formData = new FormData();
    mediaFiles.forEach((file) => formData.append('mediaFiles', file));
    documentFiles.forEach((file) => formData.append('documentFiles', file));
    formData.append('title', title.trim());
    formData.append('outputType', outputType);
    if (description.trim()) formData.append('description', description.trim());

    setLoading(true);
    try {
      let submissionKey = localStorage.getItem(SUBMISSION_KEY_STORAGE_KEY);
      if (!submissionKey) {
        submissionKey = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        localStorage.setItem(SUBMISSION_KEY_STORAGE_KEY, submissionKey);
      }
      const response = await fetch(`${API_BASE_URL}api/audio/multi-source`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'X-AudioScholar-Client': 'WEB',
          'X-Idempotency-Key': submissionKey,
        },
        body: formData,
      });
      const responseForTextFallback = response.clone();
      let data = {};
      try {
        data = await response.json();
      } catch {
        try {
          const text = await responseForTextFallback.text();
          data = { message: text || `Upload failed with status ${response.status}` };
        } catch {
          data = { message: `Upload failed with status ${response.status}` };
        }
      }
      if (!response.ok) {
        if (response.status === 401) {
          handleUnauthorized();
        }
        throw new Error(data.message || `Upload failed with status ${response.status}`);
      }
      localStorage.removeItem(SUBMISSION_KEY_STORAGE_KEY);
      localStorage.setItem(ACTIVE_JOB_STORAGE_KEY, data.jobId);
      setJob(data);
      mergeRecentJob(data);
      setLoading(false);
      if (TERMINAL_JOB_STATUSES.has(data.status)) {
        localStorage.removeItem(ACTIVE_JOB_STORAGE_KEY);
      } else {
        await pollJob(data.jobId, token);
      }
    } catch (err) {
      setFormError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="relative min-h-screen flex flex-col">
      <UploadProgressIndicator loading={loading} job={job} />

      <Header />
      <main className="flex-grow flex items-center justify-center py-12 bg-gray-50 dark:bg-gray-900 transition-colors duration-200">
        <div className="container mx-auto px-4 max-w-4xl">
          <div className="bg-white dark:bg-gray-800 rounded-lg shadow-xl p-8 md:p-10 transition-colors duration-200">
            <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-3 mb-6">
              <div>
                <h1 className="text-3xl font-bold text-gray-800 dark:text-white">Multi-Source Upload</h1>
                <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">Upload 2–5 audio, video, or document sources in any combination.</p>
              </div>
              <Link to="/upload" className="text-sm font-medium text-teal-700 dark:text-teal-300 hover:underline">
                Single upload
              </Link>
            </div>

            <input
              ref={mediaInputRef}
              type="file"
              multiple
              className="hidden"
              accept={MEDIA_ACCEPT}
              onChange={(event) => addFiles(event, 'media')}
              disabled={loading || hasActiveJob}
            />
            <input
              ref={documentInputRef}
              type="file"
              multiple
              className="hidden"
              accept={DOCUMENT_ACCEPT}
              onChange={(event) => addFiles(event, 'document')}
              disabled={loading || hasActiveJob}
            />

            <div className="mb-4">
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1 font-semibold">Audio/Video Sources</label>
              <MultiSourceFilePicker
                label="Click to select audio or video files"
                supportedLabel={MEDIA_LABEL}
				disabled={loading || hasActiveJob || remainingSlots <= 0}
				onPick={() => !loading && !hasActiveJob && remainingSlots > 0 && mediaInputRef.current?.click()}
              />
              {mediaFiles.length > 0 && <SourceFileList files={mediaFiles} kind="media" loading={loading || hasActiveJob} onRemove={removeMediaFile} />}
              {mediaError && <p className="mt-2 text-sm text-red-600 dark:text-red-400">{mediaError}</p>}
            </div>

            <div className="mb-6">
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1 font-semibold">Document Sources</label>
              <MultiSourceFilePicker
                label="Click to select document files"
                supportedLabel={DOCUMENT_LABEL}
				disabled={loading || hasActiveJob || remainingSlots <= 0}
				onPick={() => !loading && !hasActiveJob && remainingSlots > 0 && documentInputRef.current?.click()}
              />
              {documentFiles.length > 0 && <SourceFileList files={documentFiles} kind="document" offset={mediaFiles.length} loading={loading || hasActiveJob} onRemove={removeDocumentFile} />}
              {documentError && <p className="mt-2 text-sm text-red-600 dark:text-red-400">{documentError}</p>}
            </div>

            <form onSubmit={submit} className="space-y-6 border-t border-gray-200 dark:border-gray-700 pt-6">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <input className="w-full px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg dark:bg-gray-700 dark:text-white" placeholder="Merged summary title" value={title} onChange={(e) => setTitle(e.target.value)} disabled={loading || hasActiveJob} />
                <input className="w-full px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg dark:bg-gray-700 dark:text-white" placeholder="Description (optional)" value={description} onChange={(e) => setDescription(e.target.value)} disabled={loading || hasActiveJob} />
              </div>

              <div>
                <div className="flex items-center justify-between mb-2">
                  <label className="block text-sm font-bold text-gray-700 dark:text-gray-300">Output Type</label>
                  <span className="text-xs text-gray-500 dark:text-gray-400">Required</span>
                </div>
                <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
                  {OUTPUT_TYPES.map((option) => (
                    <button
                      key={option.value}
                      type="button"
                      onClick={() => setOutputType(option.value)}
                      className={`text-left border rounded-lg p-4 transition ${outputType === option.value ? 'border-teal-500 bg-teal-50 dark:bg-teal-900/20 ring-2 ring-teal-200' : 'border-gray-200 dark:border-gray-600 hover:border-teal-300'}`}
                      disabled={loading || hasActiveJob}
                    >
                      <div className="font-semibold text-gray-900 dark:text-white">{option.title}</div>
                      <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">{option.description}</p>
                    </button>
                  ))}
                </div>
              </div>

              <div className="min-h-6">
                {formError && <p className="text-sm text-red-600 dark:text-red-400 text-center">{formError}</p>}
                <DeferredStatusIndicator job={job} />
                <MergeStatusIndicator job={job} />
				{polling && <p className="text-sm text-teal-700 dark:text-teal-300 text-center flex items-center justify-center gap-1"><FiLoader className="animate-spin" /> Checking processing status...</p>}
              </div>

			  <button type="submit" disabled={loading || hasActiveJob || sourceCount < 2 || !title.trim() || !outputType} className="w-full bg-[#2D8A8A] text-white py-3 px-4 rounded-lg font-medium flex items-center justify-center gap-2 disabled:opacity-50">
                {loading ? <><FiLoader className="animate-spin" /> Processing sources...</> : <><FiUpload /> Upload and Merge</>}
              </button>
            </form>

            <MergedSummaryScreen job={job} />

            {recentJobs.length > 0 && (
              <section className="mt-8 border-t border-gray-200 dark:border-gray-700 pt-6">
                <h2 className="text-lg font-semibold text-gray-900 dark:text-white mb-3">Recent multi-source jobs</h2>
                <div className="space-y-2">
                  {recentJobs.slice(0, 10).map((recentJob) => (
                    <button
                      key={recentJob.jobId}
                      type="button"
                      onClick={() => openRecentJob(recentJob)}
                      className="w-full flex items-center justify-between gap-4 text-left border border-gray-200 dark:border-gray-700 rounded-lg px-4 py-3 hover:border-teal-400"
                    >
                      <span className="min-w-0">
                        <span className="block font-medium text-gray-900 dark:text-white truncate">{recentJob.title || 'Merged Lecture Summary'}</span>
                        <span className="block text-xs text-gray-500 dark:text-gray-400">{recentJob.sourceCount || recentJob.sourceFiles?.length || 0} sources</span>
                      </span>
                      <span className={`shrink-0 text-xs font-semibold ${recentJob.status === 'FAILED' ? 'text-red-600' : recentJob.status === 'COMPLETE' ? 'text-green-600' : 'text-amber-600'}`}>
                        {recentJob.status}
                      </span>
                    </button>
                  ))}
                </div>
              </section>
            )}
          </div>
        </div>
      </main>
    </div>
  );
};

export default MultiSourceUploadScreen;
