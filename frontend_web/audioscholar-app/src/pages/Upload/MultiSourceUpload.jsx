import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { FiCheckCircle, FiFile, FiLoader, FiUpload, FiXCircle } from 'react-icons/fi';
import { API_BASE_URL } from '../../services/authService';
import { Header } from '../Home/HomePage';
import ReactMarkdown from 'react-markdown';

const OUTPUT_TYPES = [
  { value: 'NOTES', title: 'Notes', description: 'Detailed lecture notes from every source.' },
  { value: 'STUDY_MATERIAL', title: 'Study Material', description: 'A unified study guide with definitions and structure.' },
  { value: 'REVIEW_MATERIAL', title: 'Review Material', description: 'A concise reviewer for quick recall.' },
];

const VALID_TYPES = [
  'audio/mpeg', 'audio/mp3', 'audio/wav', 'audio/x-wav', 'audio/aac', 'audio/x-aac',
  'audio/ogg', 'application/ogg', 'audio/flac', 'audio/x-flac', 'audio/aiff', 'audio/x-aiff',
  'audio/mp4', 'audio/m4a', 'video/mp4', 'video/webm', 'video/quicktime',
];
const VALID_EXTENSIONS = ['mp3', 'wav', 'aac', 'ogg', 'flac', 'aiff', 'm4a', 'mp4', 'webm', 'mov'];

const sourceLabel = (index) => `Source ${String.fromCharCode(65 + index)}`;

const MultiSourceUpload = () => {
  const [files, setFiles] = useState([]);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [outputType, setOutputType] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [job, setJob] = useState(null);
  const inputRef = useRef(null);

  const onFilesSelected = (event) => {
    setError('');
    setJob(null);
    const selected = Array.from(event.target.files || []);
    const next = [...files, ...selected].slice(0, 5);
    const invalid = next.find((file) => {
      const ext = file.name.split('.').pop().toLowerCase();
      return !VALID_TYPES.includes((file.type || '').toLowerCase()) && !VALID_EXTENSIONS.includes(ext);
    });
    if (invalid) {
      setError(`Unsupported file: ${invalid.name}`);
      return;
    }
    setFiles(next);
    if (!title && next[0]) {
      setTitle(next[0].name.replace(/\.[^/.]+$/, ''));
    }
  };

  const removeFile = (index) => {
    setFiles((current) => current.filter((_, itemIndex) => itemIndex !== index));
    setJob(null);
  };

  const submit = async (event) => {
    event.preventDefault();
    setError('');
    setJob(null);
    if (files.length < 2) {
      setError('Select at least two sources.');
      return;
    }
    if (!title.trim()) {
      setError('Enter a title for the merged summary.');
      return;
    }
    if (!outputType) {
      setError('Select an output type.');
      return;
    }
    const token = localStorage.getItem('AuthToken');
    if (!token) {
      setError('Please sign in again before uploading.');
      return;
    }

    const formData = new FormData();
    files.forEach((file) => formData.append('files', file));
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
      const data = await response.json();
      if (!response.ok) {
        throw new Error(data.message || `Upload failed with status ${response.status}`);
      }
      setJob(data);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      <Header />
      <main className="container mx-auto px-4 py-10 max-w-5xl">
        <div className="bg-white dark:bg-gray-800 rounded-lg shadow-xl p-8">
          <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 mb-6">
            <div>
              <h1 className="text-3xl font-bold text-gray-900 dark:text-white">Multi-Source Upload</h1>
              <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">Combine two to five lecture audio or video sources into one unified summary.</p>
            </div>
            <Link to="/upload" className="text-sm font-medium text-teal-700 dark:text-teal-300 hover:underline">Single upload</Link>
          </div>

          <form onSubmit={submit} className="space-y-6">
            <input ref={inputRef} type="file" multiple className="hidden" accept={VALID_EXTENSIONS.map((ext) => `.${ext}`).join(',')} onChange={onFilesSelected} disabled={loading} />
            <button
              type="button"
              onClick={() => inputRef.current?.click()}
              disabled={loading || files.length >= 5}
              className="w-full border-2 border-dashed border-gray-300 dark:border-gray-600 rounded-lg p-8 text-center hover:border-teal-400 dark:hover:border-teal-500 transition disabled:opacity-60"
            >
              <FiUpload className="mx-auto h-10 w-10 text-gray-400" />
              <span className="block mt-2 text-gray-700 dark:text-gray-200 font-medium">Select audio or video sources</span>
              <span className="block text-xs text-gray-500 mt-1">Supports: {VALID_EXTENSIONS.join(', ').toUpperCase()}</span>
            </button>

            {files.length > 0 && (
              <div className="space-y-2">
                {files.map((file, index) => (
                  <div key={`${file.name}-${index}`} className="flex items-center justify-between border border-gray-200 dark:border-gray-700 rounded-lg p-3">
                    <div className="flex items-center min-w-0">
                      <span className="shrink-0 text-xs font-semibold bg-teal-100 text-teal-800 px-2 py-1 rounded mr-3">{sourceLabel(index)}</span>
                      <FiFile className="shrink-0 h-5 w-5 text-gray-400 mr-2" />
                      <span className="truncate text-sm text-gray-800 dark:text-gray-100">{file.name}</span>
                    </div>
                    <button type="button" onClick={() => removeFile(index)} disabled={loading} className="text-red-600 hover:text-red-800 p-1" title="Remove source">
                      <FiXCircle />
                    </button>
                  </div>
                ))}
              </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
              <input className="w-full px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg dark:bg-gray-700 dark:text-white" placeholder="Merged summary title" value={title} onChange={(e) => setTitle(e.target.value)} disabled={loading} />
              <input className="w-full px-4 py-2 border border-gray-300 dark:border-gray-600 rounded-lg dark:bg-gray-700 dark:text-white" placeholder="Description (optional)" value={description} onChange={(e) => setDescription(e.target.value)} disabled={loading} />
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

            {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}
            <button type="submit" disabled={loading || files.length < 2 || !title.trim() || !outputType} className="w-full bg-[#2D8A8A] text-white py-3 px-4 rounded-lg font-medium flex items-center justify-center gap-2 disabled:opacity-50">
              {loading ? <><FiLoader className="animate-spin" /> Processing sources...</> : <><FiUpload /> Upload and Merge</>}
            </button>
          </form>

          {job && (
            <div className="mt-8 border-t border-gray-200 dark:border-gray-700 pt-6">
              <div className="flex items-center gap-2 text-green-700 dark:text-green-300 mb-4">
                <FiCheckCircle />
                <span className="font-medium">Merged summary generated</span>
              </div>
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
      </main>
    </div>
  );
};

export default MultiSourceUpload;
