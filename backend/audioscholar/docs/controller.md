# Controller Module

## 1. Module Overview

The Controller module is the entry point for all API requests to the AudioScholar backend. It is responsible for handling incoming HTTP requests, routing them to the appropriate services, and formatting the responses to be sent back to the client. This module defines the application's RESTful API endpoints, manages request/response formats using Data Transfer Objects (DTOs), and enforces security constraints on a per-endpoint basis using Spring Security.

## 2. Key Classes & Components

| Class / Component | Description |
| --- | --- |
| [`AdminController.java`](src/main/java/edu/cit/audioscholar/controller/AdminController.java) | Exposes endpoints for administrative actions, such as user management and system health checks. All endpoints are restricted to users with the 'ADMIN' role. |
| [`AnalyticsController.java`](src/main/java/edu/cit/audioscholar/controller/AnalyticsController.java) | Provides endpoints for retrieving system-wide analytics and usage data. Access is restricted to administrators. |
| [`AudioController.java`](src/main/java/edu/cit/audioscholar/controller/AudioController.java) | Handles the uploading of audio and PowerPoint files, as well as managing audio metadata and recordings for authenticated users. |
| [`AuthController.java`](src/main/java/edu/cit/audioscholar/controller/AuthController.java) | Manages user authentication, registration, and token verification across different providers (e.g., Google, GitHub, email/password). |
| [`GeminiController.java`](src/main/java/edu/cit/audioscholar/controller/GeminiController.java) | A simple controller designed for testing and verifying connectivity with the external Gemini AI service. |
| [`HomeController.java`](src/main/java/edu/cit/audioscholar/controller/HomeController.java) | Redirects root traffic (`/`) to the hosted status page and serves the local `/status` page backed by UptimeRobot monitor data. |
| [`RecommendationController.java`](src/main/java/edu/cit/audioscholar/controller/RecommendationController.java) | Manages the retrieval and deletion of learning recommendations generated for specific audio recordings. |
| [`SummaryController.java`](src/main/java/edu/cit/audioscholar/controller/SummaryController.java) | Provides CRUD (Create, Read, Update, Delete) operations for lecture summaries associated with audio recordings. |
| [`UserController.java`](src/main/java/edu/cit/audioscholar/controller/UserController.java) | Handles general user-related operations, including retrieving user data, managing FCM tokens for notifications, and role updates. |
| [`UserNoteController.java`](src/main/java/edu/cit/audioscholar/controller/UserNoteController.java) | Manages CRUD operations for personal notes that users can create and associate with specific audio recordings. |
| [`UserProfileController.java`](src/main/java/edu/cit/audioscholar/controller/UserProfileController.java) | Manages the profile of the currently authenticated user, including fetching, updating details, and uploading an avatar. |

## 3. Responsibilities & Logic Flow

**Key Responsibilities:**
- **API Endpoint Definition:** Exposes all public REST API endpoints for the application under the `/api` path.
- **Request Handling:** Receives and validates incoming HTTP requests, extracting parameters, headers, and body content.
- **Authentication & Authorization:** Works with the `Security` module to ensure that only authenticated and authorized users can access protected endpoints.
- **Data Transfer:** Uses DTOs from the `dto` module to structure data for requests and responses, decoupling the API from the internal data models.
- **Service Delegation:** Delegates the core business logic for each request to the appropriate class in the `Service` module.
- **Response Formatting:** Serializes the results from the service layer (or any errors) into JSON format and sends them back to the client with the correct HTTP status code.

**Example Workflow: Audio File Upload**
1.  **Step 1:** An incoming `POST` request with a multipart file is received by the `uploadAudio` method in [`AudioController.java`](src/main/java/edu/cit/audioscholar/controller/AudioController.java).
2.  **Step 2:** The controller validates the user's authentication status and checks the file's content type to ensure it is a supported audio format.
3.  **Step 3:** It calls the `queueFilesForUpload` method from the [`AudioProcessingService`](src/main/java/edu/cit/audioscholar/service/AudioProcessingService.java) to handle the file storage and create initial metadata.
4.  **Step 4:** The controller receives an `AudioMetadata` object back from the service.
5.  **Step 5:** The result is processed and returned to the client as a JSON response with an `HTTP 202 (Accepted)` status, indicating that the processing has begun.

## 4. Dependencies

**Internal Dependencies:**
- **Service Module:** The Controller module is heavily dependent on the Service module to execute all business logic. Controllers orchestrate calls to services but do not contain business logic themselves.
- **DTO Module:** Used extensively for defining the structure of API request and response bodies.
- **Model Module:** Occasionally used when methods directly return a model object, though returning DTOs is the more common pattern.
- **Security Module:** Relies on security components like `JwtTokenProvider` to secure endpoints and process authentication details.

**External Dependencies:**
- **Spring Web (`spring-boot-starter-web`)**: Provides the core framework for building REST controllers, handling HTTP requests, and managing MVC components.
- **Spring Security (`spring-boot-starter-security`)**: Used for securing endpoints with annotations like `@PreAuthorize` and for accessing the current user's authentication context.
- **Jakarta Validation API (`jakarta.validation-api`)**: Used for validating request bodies with annotations like `@Valid`.

## 5. Configuration

The Controller module itself does not require specific, dedicated configuration properties. However, its behavior is influenced by the application's security and web server configurations. Key related properties are managed in the `config` module and defined in `application.properties`. There are no environment variables exclusive to this module's direct operation.
