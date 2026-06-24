export const SUCCESS_STATUSES = ['COMPLETE', 'COMPLETED'];
export const WARNING_STATUSES = ['COMPLETED_WITH_WARNINGS'];
export const FAILURE_STATUSES = [
  'FAILED',
  'SUMMARY_FAILED',
  'PROCESSING_HALTED_UNSUITABLE_CONTENT',
  'PROCESSING_HALTED_NO_SPEECH',
];
export const UPLOADING_STATUSES = [
  'UPLOAD_PENDING',
  'UPLOAD_IN_PROGRESS',
  'UPLOADING_TO_STORAGE',
  'UPLOADED',
];
export const PROCESSING_STATUSES = [
  'PROCESSING_QUEUED',
  'TRANSCRIBING',
  'PDF_CONVERTING',
  'PDF_CONVERTING_API',
  'TRANSCRIPTION_COMPLETE',
  'PDF_CONVERSION_COMPLETE',
  'SUMMARIZATION_QUEUED',
  'SUMMARIZING',
  'SUMMARY_COMPLETE',
  'RECOMMENDATIONS_QUEUED',
  'GENERATING_RECOMMENDATIONS',
  'PROCESSING',
];
export const TERMINAL_STATUSES = [
  ...SUCCESS_STATUSES,
  ...WARNING_STATUSES,
  ...FAILURE_STATUSES,
];
export const RETRYABLE_FAILURE_STATUSES = ['FAILED', 'SUMMARY_FAILED'];

export const normalizeProcessingStatus = (status) => status?.toUpperCase?.() ?? 'UNKNOWN';

export const isUploadStatus = (status) => UPLOADING_STATUSES.includes(normalizeProcessingStatus(status));

export const isProcessingStatus = (status) => PROCESSING_STATUSES.includes(normalizeProcessingStatus(status));

export const isActiveProcessingStatus = (status) => {
  const normalized = normalizeProcessingStatus(status);
  return UPLOADING_STATUSES.includes(normalized) || PROCESSING_STATUSES.includes(normalized);
};

export const isTerminalProcessingStatus = (status) => TERMINAL_STATUSES.includes(normalizeProcessingStatus(status));

export const isFailureProcessingStatus = (status) => FAILURE_STATUSES.includes(normalizeProcessingStatus(status));

export const canRetryProcessingStatus = (status) => (
  RETRYABLE_FAILURE_STATUSES.includes(normalizeProcessingStatus(status))
);

export const getProcessingStatusCopy = (status) => {
  const normalized = normalizeProcessingStatus(status);
  const copy = {
    label: 'Unknown',
    progress: null,
    tone: 'unknown',
    isSpinning: false,
  };

  if (SUCCESS_STATUSES.includes(normalized)) {
    return { ...copy, label: 'Completed', tone: 'success' };
  }

  if (WARNING_STATUSES.includes(normalized)) {
    return { ...copy, label: 'Completed with Warnings', tone: 'warning' };
  }

  if (FAILURE_STATUSES.includes(normalized)) {
    return { ...copy, label: 'Failed', tone: 'failure' };
  }

  if (UPLOADING_STATUSES.includes(normalized)) {
    return {
      ...copy,
      label: normalized === 'UPLOADED' ? 'Upload Complete' : 'Uploading',
      progress: normalized === 'UPLOADED' ? 'Starting processing...' : null,
      tone: 'upload',
      isSpinning: true,
    };
  }

  if (PROCESSING_STATUSES.includes(normalized)) {
    const statusMap = {
      PROCESSING_QUEUED: 'Queued for Processing',
      TRANSCRIBING: 'Transcribing Audio',
      PDF_CONVERTING: 'Converting Document',
      PDF_CONVERTING_API: 'Converting Document',
      TRANSCRIPTION_COMPLETE: 'Processing Transcript',
      PDF_CONVERSION_COMPLETE: 'Processing Document',
      SUMMARIZATION_QUEUED: 'Queued for Summarization',
      SUMMARIZING: 'Generating Summary',
      SUMMARY_COMPLETE: 'Preparing Recommendations',
      RECOMMENDATIONS_QUEUED: 'Queued for Recommendations',
      GENERATING_RECOMMENDATIONS: 'Generating Recommendations',
      PROCESSING: 'Processing',
    };
    const progressMap = {
      TRANSCRIBING: 'This may take a few minutes...',
      SUMMARIZING: 'Generating AI summary...',
      SUMMARY_COMPLETE: 'Summary is ready. Finishing recommendations...',
      GENERATING_RECOMMENDATIONS: 'Finding relevant learning resources...',
    };

    return {
      ...copy,
      label: statusMap[normalized] || 'Processing',
      progress: progressMap[normalized] || null,
      tone: 'processing',
      isSpinning: true,
    };
  }

  return copy;
};
