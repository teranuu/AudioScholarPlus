import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { FiCheckCircle, FiFile, FiFileText, FiLoader, FiUpload, FiXCircle } from 'react-icons/fi';
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

const MultiSourceUpload = () => {
  const [mediaFiles, setMediaFiles] = useState([]);
  const [documentFiles, setDocumentFiles] = useState([]);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [outputType, setOutputType] = useState('');
  const [mediaError, setMediaError] = useState('');
  const [documentError, setDocumentError] = useState('');
  const [formError, setFormError] = useState('');
  const [loading, setLoading] = useState(false);
  const [job, setJob] = useState(null);
  const mediaInputRef = useRef(null);
  const documentInputRef = useRef(null);
  const navigate = useNavigate();

  const sourceCount = mediaFiles.length + documentFiles.length;
  const remainingSlots = MAX_TOTAL_SOURCES - sourceCount;

  useEffect(() => {
    const handleBeforeUnload = (event) => {
      event.preventDefault();
      event.returnValue = 'Upload in progress. Are you sure you want to leave?';
      return 'Upload in progress. Are you sure you want to leave?';
    };

    if (loading) {
      window.addEventListener('beforeunload', handleBeforeUnload);
    } else {
      window.removeEventListener('beforeunload', handleBeforeUnload);
    }

    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [loading]);

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

  const submit = async (event) => {
    event.preventDefault();
    setMediaError('');
    setDocumentError('');
    setFormError('');
    setJob(null);

    if (mediaFiles.length < 2) {
      setMediaError('Select at least two audio or video sources.');
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
      const response = await fetch(`${API_BASE_URL}api/audio/multi-source`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
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
          localStorage.removeItem('AuthToken');
          localStorage.removeItem('userId');
          navigate('/signin');
        }
        throw new Error(data.message || `Upload failed with status ${response.status}`);
      }
      setJob(data);
    } catch (err) {
      setFormError(err.message);
    } finally {
      setLoading(false);
    }
  };

  const renderFileList = (files, kind, removeFile, offset = 0) => (
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
          <button type="button" onClick={() => removeFile(index)} disabled={loading} className="text-red-600 hover:text-red-800 p-1" title="Remove source">
            <FiXCircle />
          </button>
        </div>
      ))}
    </div>
  );

  return (
    <div className="relative min-h-screen flex flex-col">
      {loading && (
        <div className="fixed inset-0 bg-black bg-opacity-50 z-50 flex flex-col items-center justify-center">
          <FiLoader className="animate-spin h-10 w-10 text-white mb-3" />
          <p className="text-white text-lg font-medium">Processing sources, please wait...</p>
          <p className="text-gray-300 text-sm mt-1">Navigating away may interrupt the upload.</p>
        </div>
      )}

      <Header />
      <main className="flex-grow flex items-center justify-center py-12 bg-gray-50 dark:bg-gray-900 transition-colors duration-200">
        <div className="container mx-auto px-4 max-w-4xl">
          <div className="bg-white dark:bg-gray-800 rounded-lg shadow-xl p-8 md:p-10 transition-colors duration-200">
            <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-3 mb-6">
              <div>
                <h1 className="text-3xl font-bold text-gray-800 dark:text-white">Multi-Source Upload</h1>
                <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">Upload at least two audio or video sources, with optional document context.</p>
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
              disabled={loading}
            />
            <input
              ref={documentInputRef}
              type="file"
              multiple
              className="hidden"
              accept={DOCUMENT_ACCEPT}
              onChange={(event) => addFiles(event, 'document')}
              disabled={loading}
            />

            <div className="mb-4">
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1 font-semibold">Audio/Video Sources (Required)</label>
              <div
                onClick={() => !loading && remainingSlots > 0 && mediaInputRef.current?.click()}
                className={`border-2 border-dashed border-gray-300 dark:border-gray-600 rounded-lg p-6 text-center transition-colors duration-200 ${loading || remainingSlots <= 0 ? 'opacity-60 cursor-not-allowed bg-gray-100 dark:bg-gray-700' : 'cursor-pointer hover:bg-gray-100 dark:hover:bg-gray-700 hover:border-teal-400 dark:hover:border-teal-400'}`}
              >
                <FiUpload className="mx-auto h-10 w-10 text-gray-400 dark:text-gray-500" />
                <p className="text-gray-600 dark:text-gray-300 mt-2 mb-1 text-sm">Click to select audio or video files</p>
                <p className="text-xs text-gray-500 dark:text-gray-400">Supports: {MEDIA_LABEL}</p>
              </div>
              {mediaFiles.length > 0 && renderFileList(mediaFiles, 'media', removeMediaFile)}
              {mediaError && <p className="mt-2 text-sm text-red-600 dark:text-red-400">{mediaError}</p>}
            </div>

            <div className="mb-6">
              <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1 font-semibold">Document Sources (Optional)</label>
              <div
                onClick={() => !loading && remainingSlots > 0 && documentInputRef.current?.click()}
                className={`border-2 border-dashed border-gray-300 dark:border-gray-600 rounded-lg p-6 text-center transition-colors duration-200 ${loading || remainingSlots <= 0 ? 'opacity-60 cursor-not-allowed bg-gray-100 dark:bg-gray-700' : 'cursor-pointer hover:bg-gray-100 dark:hover:bg-gray-700 hover:border-teal-400 dark:hover:border-teal-400'}`}
              >
                <FiUpload className="mx-auto h-10 w-10 text-gray-400 dark:text-gray-500" />
                <p className="text-gray-600 dark:text-gray-300 mt-2 mb-1 text-sm">Click to select document files</p>
                <p className="text-xs text-gray-500 dark:text-gray-400">Supports: {DOCUMENT_LABEL}</p>
              </div>
              {documentFiles.length > 0 && renderFileList(documentFiles, 'document', removeDocumentFile, mediaFiles.length)}
              {documentError && <p className="mt-2 text-sm text-red-600 dark:text-red-400">{documentError}</p>}
            </div>

            <form onSubmit={submit} className="space-y-6 border-t border-gray-200 dark:border-gray-700 pt-6">
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <input className="w-full px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg dark:bg-gray-700 dark:text-white" placeholder="Merged summary title" value={title} onChange={(e) => setTitle(e.target.value)} disabled={loading} />
                <input className="w-full px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg dark:bg-gray-700 dark:text-white" placeholder="Description (optional)" value={description} onChange={(e) => setDescription(e.target.value)} disabled={loading} />
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
                      disabled={loading}
                    >
                      <div className="font-semibold text-gray-900 dark:text-white">{option.title}</div>
                      <p className="text-xs text-gray-500 dark:text-gray-400 mt-1">{option.description}</p>
                    </button>
                  ))}
                </div>
              </div>

              <div className="h-6">
                {formError && <p className="text-sm text-red-600 dark:text-red-400 text-center">{formError}</p>}
                {job && <p className="text-sm text-green-600 dark:text-green-400 text-center flex items-center justify-center gap-1"><FiCheckCircle /> Merged summary generated.</p>}
              </div>

              <button type="submit" disabled={loading || mediaFiles.length < 2 || !title.trim() || !outputType} className="w-full bg-[#2D8A8A] text-white py-3 px-4 rounded-lg font-medium flex items-center justify-center gap-2 disabled:opacity-50">
                {loading ? <><FiLoader className="animate-spin" /> Processing sources...</> : <><FiUpload /> Upload and Merge</>}
              </button>
            </form>

            {job && (
              <div className="mt-8 border-t border-gray-200 dark:border-gray-700 pt-6">
                <div className="flex flex-wrap gap-2 mb-4">
                  {(job.sourceFiles || []).map((source) => (
                    <span key={source.sourceFileId} className="text-xs bg-gray-100 dark:bg-gray-700 text-gray-700 dark:text-gray-200 rounded px-2 py-1">{source.sourceLabel}: {source.fileName}</span>
                  ))}
                </div>
                <div className="prose prose-sm max-w-none dark:prose-invert">
                  <ReactMarkdown>{job.mergedSummary?.formattedSummaryText || 'Merged summary is available.'}</ReactMarkdown>
                </div>
              </div>
            )}
          </div>
        </div>
      </main>
    </div>
  );
};

export default MultiSourceUpload;
