// Determine the API base URL from environment variable or fallback to localhost
const getApiBaseUrl = () => {
    // Use VITE_API_URL environment variable if set, otherwise fallback to localhost
    const envUrl = import.meta.env.VITE_API_URL;
    if (envUrl) {
        // Ensure URL ends with trailing slash
        return envUrl.endsWith('/') ? envUrl : `${envUrl}/`;
    }
    // Fallback for local development
    return 'http://localhost:8080/';
};

export const API_BASE_URL = getApiBaseUrl();

const AUTH_REQUEST_TIMEOUT_MS = 35000;
const AUTH_REQUEST_ATTEMPTS = 2;

const delay = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds));

const fetchWithTimeout = async (url, options, timeoutMs) => {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

    try {
        return await fetch(url, { ...options, signal: controller.signal });
    } finally {
        clearTimeout(timeoutId);
    }
};

const readJsonResponse = async (response) => {
    const responseText = await response.text();
    if (!responseText) return {};

    try {
        return JSON.parse(responseText);
    } catch {
        throw new Error(`Backend returned an invalid response (${response.status}).`);
    }
};

const backendUnavailableError = (cause) => {
    const error = new Error(
        'Google sign-in succeeded, but the AudioScholar server is unavailable. Please wait a minute and try again.'
    );
    error.code = 'backend/unavailable';
    error.cause = cause;
    return error;
};

export const warmBackend = async () => {
    try {
        await fetchWithTimeout(`${API_BASE_URL}actuator/health`, {
            method: 'GET',
            cache: 'no-store',
        }, 10000);
    } catch {
        // A best-effort request starts a sleeping Render instance before sign-in.
    }
};

/**
 * Sends the Firebase ID token obtained from frontend Firebase authentication
 * to the backend for verification and to receive an API JWT.
 * @param {string} idToken - The Firebase ID token.
 * @returns {Promise<object>} - A promise that resolves with the backend's response (e.g., { success, message, token, userId })
 */
export const verifyFirebaseTokenWithBackend = async (idToken) => {
    const VERIFY_ENDPOINT_PATH = 'api/auth/verify-firebase-token';

    for (let attempt = 1; attempt <= AUTH_REQUEST_ATTEMPTS; attempt += 1) {
        try {
            const response = await fetchWithTimeout(`${API_BASE_URL}${VERIFY_ENDPOINT_PATH}`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ idToken }),
            }, AUTH_REQUEST_TIMEOUT_MS);

            const responseData = await readJsonResponse(response);

            if (!response.ok) {
                const error = new Error(responseData.message || `Backend verification failed: ${response.statusText}`);
                error.status = response.status;
                error.data = responseData;
                throw error;
            }

            if (!responseData.success || !responseData.token) {
                const error = new Error(responseData.message || 'Backend verification succeeded but response format is incorrect or token missing.');
                error.data = responseData;
                throw error;
            }

            return responseData;
        } catch (error) {
            const isNetworkFailure = error instanceof TypeError || error.name === 'AbortError';
            if (!isNetworkFailure) {
                console.error("Error during backend Firebase token verification API call:", error);
                throw error;
            }

            if (attempt < AUTH_REQUEST_ATTEMPTS) {
                await delay(1500);
                continue;
            }

            const unavailableError = backendUnavailableError(error);
            console.error("Error during backend Firebase token verification API call:", unavailableError);
            throw unavailableError;
        }
    }
};

/**
 * Sends the Google ID token obtained from frontend Firebase authentication
 * (specifically via Google Sign-In) to the backend for verification
 * and to receive an API JWT.
 * @param {string} googleIdToken - The raw Google ID token.
 * @returns {Promise<object>} - A promise that resolves with the backend's response.
 */
export const verifyGoogleTokenWithBackend = async (googleIdToken) => {
    const VERIFY_ENDPOINT_PATH = 'api/auth/verify-google-token';

    try {
        const response = await fetch(`${API_BASE_URL}${VERIFY_ENDPOINT_PATH}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ idToken: googleIdToken }),
        });

        const responseData = await response.json();

        if (!response.ok) {
            const error = new Error(responseData.message || `Backend Google verification failed: ${response.statusText}`);
            error.status = response.status;
            error.data = responseData;
            throw error;
        }

        if (!responseData.success || !responseData.token) {
            const error = new Error(responseData.message || 'Backend Google verification succeeded but response format is incorrect or token missing.');
            error.data = responseData;
            throw error;
        }

        return responseData;

    } catch (error) {
        console.error("Error during backend Google token verification API call:", error);
        throw error;
    }
};

export const signUp = async (userData) => {
    const SIGNUP_ENDPOINT_PATH = 'api/auth/register';

    try {
        const response = await fetch(`${API_BASE_URL}${SIGNUP_ENDPOINT_PATH}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(userData),
        });

        const responseData = await response.json();

        if (!response.ok) {
            console.error('Backend sign up error response:', responseData);
            const error = new Error(responseData.message || `HTTP error! status: ${response.status}`);
            error.status = response.status;
            error.data = responseData;
            throw error;
        }

        return responseData;

    } catch (error) {
        console.error("Error during sign up API call:", error);
        throw error;
    }
};

