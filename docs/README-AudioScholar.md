# AudioScholar: Transforming Audio into Actionable Insights for Learners

**AudioScholar** is a comprehensive educational technology platform designed to streamline the study process for college students. By leveraging advanced AI, it transforms audio lecture recordings into structured, actionable study summaries. The system operates on a dual-platform architecture featuring a native Android application for on-the-go recording and a responsive ReactJS web interface for deep study sessions, all powered by a robust Spring Boot backend.

## Table of Contents
1. [Project Overview](#project-overview)
2. [Key Features](#key-features)
3. [Technology Stack](#technology-stack)
4. [System Requirements](#system-requirements)
5. [Deployment Instructions](#deployment-instructions)
6. [Sample Login Credentials](#sample-login-credentials)
7. [Live Demo](#live-demo)
8. [Quick Start Guide](#quick-start-guide)
9. [API Endpoints Summary](#api-endpoints-summary)

---

## Project Overview

Traditional note-taking often forces students to choose between active listening and transcribing. AudioScholar solves this by automating the capture and synthesis of lecture content.

**Core Value Proposition:**
*   **Reduces Cognitive Load:** Automates note-taking so students can focus on understanding concepts in real-time.
*   **Enhances Context:** Integrates PowerPoint slides with audio processing to generate context-aware summaries.
*   **Personalized Learning:** Recommends curated YouTube videos based on the specific topics detected in the lecture.

---

## Key Features

*   **Offline Lecture Recording:** Robust mobile audio recording capable of handling long lectures without internet access.
*   **AI-Driven Summarization:** Utilizes Google Gemini AI to generate structured summaries, key points, and glossaries.
*   **PowerPoint Integration:** Upload PPTX files to enhance summary accuracy and context.
*   **Cross-Platform Sync:** Seamless synchronization of recordings and summaries between Mobile and Web via Nhost.
*   **Learning Recommendations:** Intelligent fetching of relevant YouTube educational videos.
*   **User Management:** Secure authentication via Email/Password, Google OAuth, and GitHub OAuth.
*   **Freemium Model:** Tiered access features including background recording and advanced cloud sync for premium users.
*   **Admin Dashboard:** Comprehensive tools for user management and system analytics.

---

## Technology Stack

The project relies on a modern, scalable technology stack. Below are the exact versions used in development.

### Backend
| Component | Technology | Version |
| :--- | :--- | :--- |
| **Language** | Java | 24 |
| **Framework** | Spring Boot | 3.5.8 |
| **Build Tool** | Maven | (bundled wrapper) |
| **Database** | Firebase Firestore | `google-cloud-firestore` 3.32.2 |
| **Security** | Spring Security / JWT | `jjwt` 0.12.6 |
| **Rate Limiting** | Bucket4j | 8.9.0 |

### Frontend Web
| Component | Technology | Version |
| :--- | :--- | :--- |
| **Framework** | React | ^19.0.0 |
| **Build Tool** | Vite | ^6.4.1 |
| **Styling** | TailwindCSS | ^4.1.1 |
| **Backend Integration** | Firebase SDK | ^11.6.0 |

### Frontend Mobile
| Component | Technology | Version |
| :--- | :--- | :--- |
| **Language** | Kotlin | 1.9.x (JVM 17) |
| **SDK** | Android SDK | Compile/Target: 35, Min: 24 |
| **UI Framework** | Jetpack Compose | Compiler Ext: 1.5.15 |
| **Network** | Retrofit / OkHttp | - |

### Cloud & Services
*   **AI Integration:** Google Gemini API (Models: `gemini-2.0-flash`, `gemini-2.5-flash`)
*   **Cloud Storage:** Nhost Storage
*   **External APIs:** YouTube Data API v3, ConvertAPI
*   **Hosting:** Render (Backend), Vercel (Web Frontend)

---

## System Requirements

### Development Environment
*   **OS:** Windows, macOS, or Linux
*   **Java:** JDK 24 or higher
*   **Node.js:** v18.0.0 or higher
*   **Android Studio:** Koala Feature Drop or newer (supporting SDK 35)
*   **IDE:** IntelliJ IDEA (Backend), VS Code (Web), Android Studio (Mobile)

### Runtime Requirements
*   **Android Device:** Android 7.0 (Nougat) or higher (API Level 24+)
*   **Web Browser:** Modern browser (Chrome, Firefox, Edge, Safari)
*   **Internet Connection:** Required for syncing, summarization, and account management.

---

## Deployment Instructions

### 1. Environment Variables
The following environment variables must be configured in your deployment environment (e.g., Render dashboard, Vercel project settings, or local `.env` file).

**Backend Variables:**
```properties
UPTIME_ROBOT_API=       # API Key for Uptime Robot monitoring
NHOST_ADMIN_SECRET=     # Admin secret for Nhost storage access
YOUTUBE_API_KEY=        # Google Cloud Console API Key for YouTube Data API
GOOGLE_AI_API_KEY=      # Gemini AI API Key
GOOGLE_CLIENT_ID=       # OAuth Client ID for Google
GOOGLE_CLIENT_SECRET=   # OAuth Client Secret for Google
GITHUB_CLIENT_ID=       # OAuth Client ID for GitHub
GITHUB_CLIENT_SECRET=   # OAuth Client Secret for GitHub
JWT_SECRET=             # Secret key for signing JWT tokens
CONVERTAPI_SECRET=      # Secret for ConvertAPI (PPT to PDF)
FIREBASE_WEB_API_KEY=   # Firebase web/API key loaded from environment
FIREBASE_DATABASE_URL=  # Firebase Realtime Database URL
GOOGLE_ANDROID_CLIENT_ID= # Android OAuth client ID
NHOST_STORAGE_URL=     # Nhost storage endpoint
APP_CORS_ALLOWED_ORIGINS= # Comma-separated allowed web/mobile origins
GEMINI_API_KEYS=        # (Optional) Comma-separated list for key rotation
CONVERTAPI_SECRETS=     # (Optional) Comma-separated list for key rotation
NVD_API_KEY=             # Optional NVD key for OWASP dependency scanning
```

### 2. Backend Deployment (Render)
1.  Connect your GitHub repository to Render.
2.  Select `backend/audioscholar` as the root directory.
3.  Choose **Docker** as the runtime environment.
4.  Add the environment variables listed above.
5.  Deploy. Render will build the Docker image using the `Dockerfile` present in the directory.

### 3. Web Frontend Deployment (Vercel)
1.  Import the project into Vercel.
2.  Set the **Root Directory** to `frontend_web/audioscholar-app`.
3.  The build command should be detected automatically (`vite build`).
4.  Configure public Firebase values (`VITE_FIREBASE_*`) and the GitHub OAuth browser client ID (`VITE_GITHUB_CLIENT_ID`) through Vercel environment variables rather than hardcoding them.
5.  Deploy.

### 4. Mobile App Build
1.  Open `frontend_mobile/AudioScholar` in Android Studio.
2.  Sync Gradle project.
3.  Build > Build Bundle(s) / APK(s) > Build APK(s).
4.  Locate the APK in `app/build/outputs/apk/debug/` (or release).

---

## Sample Login Credentials

Use these credentials to test the different user roles and access levels during the defense presentation.

### ADMIN USER
*   **Email:** `admin@audioscholar.edu`
*   **Password:** Provided through the approved demo secret channel (do not commit passwords).
*   *Access:* Full system control, user management, analytics dashboard.

### REGULAR USER (Free Tier)
*   **Email:** `student@audioscholar.edu`
*   **Password:** Provided through the approved demo secret channel (do not commit passwords).
*   *Access:* Basic recording, standard summarization, foreground recording only.

### PREMIUM USER
*   **Email:** `premium@audioscholar.edu`
*   **Password:** Provided through the approved demo secret channel (do not commit passwords).
*   *Access:* Background recording, auto-cloud sync, advanced summarization models.

---

## Live Demo URLs

*   **Web Application:** [https://audioscholar.vercel.app/](https://audioscholar.vercel.app/)
*   **Backend API:** [https://it342-g3-audioscholar-onrender-com.onrender.com/](https://it342-g3-audioscholar-onrender-com.onrender.com/)
*   **GitHub Repository:** [https://github.com/MasuRii/AudioScholar](https://github.com/MasuRii/AudioScholar)
*   **Android APK Download:** [Download APK](https://drive.usercontent.google.com/download?id=1Dqqb75CKhFxc12OIsxBsc8JSiGSaFSOG&export=download)
*   **All Links (Linktree):** [https://linktr.ee/AudioScholar](https://linktr.ee/AudioScholar)
*   **User Survey:** [https://forms.cloud.microsoft/r/7fpm2evHMv](https://forms.cloud.microsoft/r/7fpm2evHMv)

---

## Quick Start Guide

1.  **Log In:** Open the mobile app or web dashboard and sign in using the **Regular User** credentials provided above.
2.  **Record a Lecture (Mobile):**
    *   Navigate to the "Record" tab.
    *   Tap the microphone icon to start recording.
    *   (Optional) Minimize the app to test background recording (Premium only).
    *   Stop the recording and give it a title (e.g., "Intro to Computer Science").
3.  **Generate Summary:**
    *   Tap on the saved recording.
    *   Select "Generate Summary". The app will upload the audio and process it via Gemini.
4.  **View Insights:**
    *   Once processing is complete, view the "Summary" tab for key points and the glossary.
    *   Check the "Recommendations" tab for related YouTube videos.

---

## API Endpoints Summary

The backend exposes a RESTful API. Key endpoints include:

*   **Authentication**
    *   `POST /auth/login` - Authenticate user and receive JWT.
    *   `POST /auth/register` - Register a new user account.
    *   `GET /oauth2/authorization/{provider}` - Initiate OAuth flow (Google/GitHub).

*   **Audio & Summaries**
    *   `POST /api/audio/upload` - Upload audio file (multipart/form-data).
    *   `GET /api/audio/{id}/summary` - Retrieve generated summary.
    *   `GET /api/audio/{id}/recommendations` - Get YouTube recommendations.

*   **User Management**
    *   `GET /api/users/profile` - Get current user profile.
    *   `PUT /api/users/profile` - Update user preferences.

*   **Admin**
    *   `GET /api/admin/users` - List all users.
    *   `PUT /api/admin/users/{id}/status` - Ban/Unban user.