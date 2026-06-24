# Authentication & Authorization

AudioScholar uses a hybrid authentication system combining **Firebase Authentication** (for identity management and OAuth handling) and a custom Backend API (for session token issuance and user data persistence).

## Overview

The authentication flow ensures that users can securely sign up, log in, and access protected resources.

- **Frontend:** React (handling forms, Firebase SDK interaction, and token storage).
- **Backend:** Spring Boot (verifying Firebase tokens, issuing custom JWTs).
- **Identity Provider:** Firebase Auth (Email/Password, Google, GitHub).

## Key Workflows

### 1. Sign Up (Email/Password)

**File:** `src/pages/Auth/SignUp/SignUp.jsx`

1.  **User Input:** User provides First Name, Last Name, Email, and Password.
2.  **Validation:** Frontend validates input (password strength, email format).
3.  **Backend Account Creation:** The frontend calls `signUp()` in `src/services/authService.js`.
    - This sends a POST request to `api/auth/register` with user details.
    - The backend creates the user in Firebase (if not exists) and creates a user profile in Firestore/Database.
4.  **Firebase Sign In:** Upon success, the frontend immediately signs the user in using `signInWithEmailAndPassword` to establish a Firebase session.
5.  **Email Verification:** A verification email is sent via `sendEmailVerification`.
6.  **Redirect:** User is redirected to a notice page (`/verify-email-notice`) instructing them to check their inbox.

### 2. Sign In (Email/Password)

**File:** `src/pages/Auth/SignIn/SignIn.jsx`

1.  **User Input:** User enters email and password.
2.  **Firebase Auth:** Frontend calls `signInWithEmailAndPassword`.
3.  **Verification Check:** System checks `user.emailVerified`. If false, login is blocked and user is asked to verify email.
4.  **Token Exchange:**
    - Frontend retrieves the Firebase ID Token (`user.getIdToken()`).
    - This token is sent to the backend via `verifyFirebaseTokenWithBackend()`.
5.  **Session Establishment:**
    - The backend verifies the ID token.
    - If valid, it returns a custom API JWT (`AuthToken`) and `userId`.
    - These are stored in `localStorage`.
6.  **Redirect:** User is redirected to `/dashboard`.

### 3. Google Sign In

**File:** `src/pages/Auth/SignIn/SignIn.jsx`

1.  **Trigger:** User clicks "Sign in with Google".
2.  **Popup:** `signInWithPopup` handles the Google OAuth flow.
3.  **Credential Retrieval:** On success, the Google ID Token is extracted from the result.
4.  **Backend Verification:** The token is sent to `api/auth/verify-google-token` (via `verifyGoogleTokenWithBackend`).
5.  **Session:** Backend returns the API JWT, which is stored in `localStorage`.

### 4. GitHub Sign In

**Files:** `src/pages/Auth/SignIn/SignIn.jsx`, `src/pages/Auth/GithubCallback/GithubAuthCallback.jsx`

1.  **Trigger:** User clicks "Sign in with GitHub".
2.  **Redirection:** User is redirected to GitHub's OAuth authorization URL.
    - Redirect URI: `window.location.origin + '/auth/github/callback'`
3.  **Callback:** GitHub redirects back to the callback URL with a `code` parameter.
4.  **Code Exchange:**
    - `GithubAuthCallback.jsx` parses the `code`.
    - It sends the code to the backend (`api/auth/verify-github-code`).
5.  **Completion:** Backend exchanges the code for a GitHub token, creates/updates the user, and returns the API JWT.

### 5. Email Verification

**File:** `src/pages/Auth/EmailVerification/EmailVerification.jsx`

- Users arriving from the email link (`/email-verification?oobCode=...`) trigger `applyActionCode(auth, oobCode)`.
- **Status Handling:** The UI displays "Verifying", "Success", or "Error" based on the operation result.
- **Edge Case:** If the link was already clicked, the system attempts to reload the user profile to check `emailVerified` status before showing an error.

## Route Protection

**File:** `src/components/common/ProtectedRoute.jsx`

Protected routes (like `/dashboard`, `/profile`) are wrapped in the `ProtectedRoute` component.

- **Check:** It checks for the presence of `AuthToken` in `localStorage`.
- **Action:**
    - If present: Renders the child component.
    - If missing: Redirects to `/signin`.

## Service Layer

**File:** `src/services/authService.js`

Contains the API calls for authentication:
- `verifyFirebaseTokenWithBackend(idToken)`: Verifies Firebase ID tokens.
- `verifyGoogleTokenWithBackend(googleIdToken)`: Verifies Google tokens.
- `signUp(userData)`: Registers a new user.
- `VITE_API_URL`: Configures the backend API base URL for local or deployed environments.