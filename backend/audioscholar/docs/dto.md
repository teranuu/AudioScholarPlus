# DTO Module

## 1. Module Overview

The DTO (Data Transfer Object) module is responsible for defining the data structures used for communication between the client and the server's API endpoints. These objects serve as plain data containers that carry information across process boundaries, ensuring a clear and decoupled contract for API requests and responses. This module is critical for standardizing data exchange, providing type safety, and enabling validation of incoming and outgoing data.

## 2. Key Classes & Components

| Class / Component | Description |
|---|---|
| `AdminUpdateUserRolesRequest.java` | A request object used by administrators to update the roles of a specific user. |
| `AdminUpdateUserStatusRequest.java` | A request object used by administrators to enable or disable a user's account. |
| `AdminUserListItemDto.java` | Represents a single user in a list returned to an administrator, containing detailed user information. |
| `AnalysisResults.java` | A container for the results of content analysis, indicating success or failure and holding keywords/topics. |
| `AudioProcessingMessage.java` | A message DTO for asynchronous audio processing tasks, typically used with a message queue. |
| `AuthResponse.java` | A generic response object for authentication-related actions, indicating success, a message, and user details. |
| `ChangePasswordRequest.java` | A request object for users to change their password. |
| `CreateUserNoteRequest.java` | A request object for creating a new note associated with a recording. |
| `FcmTokenRequest.java` | A request object to register a Firebase Cloud Messaging (FCM) token for push notifications. |
| `FirebaseTokenRequest.java` | A request object containing a Firebase ID token for authentication. |
| `GitHubCodeRequest.java` | A request object containing the authorization code from GitHub's OAuth flow. |
| `JwtAuthenticationResponse.java` | A response object containing a JWT access token upon successful authentication. |
| `LoginRequest.java` | A request object containing user credentials (email and password) for login. |
| `Monitor.java` | Represents a monitoring service's status from Uptime Robot, including uptime and status details. |
| `NhostUploadMessage.java` | A message DTO for handling file uploads asynchronously to an Nhost storage service. |
| `Pagination.java` | A generic object representing pagination details (offset, limit, total) for paginated API responses. |
| `RegistrationRequest.java` | A request object for new user registration, containing essential user details. |
| `SummaryDto.java` | A response object that represents a processed summary of a recording. |
| `UpdateRecordingRequest.java` | A request object for updating the title and description of a recording. |
| `UpdateSummaryRequest.java` | A request object for updating the content of an existing summary. |
| `UpdateUserNoteRequest.java` | A request object for updating the content and tags of an existing user note. |
| `UpdateUserProfileRequest.java` | A request object for updating a user's profile information. |
| `UpdateUserRoleRequest.java` | A request object for a user to request a role update (e.g., to premium). |
| `UptimeRobotResponse.java` | The main response object from the UptimeRobot v3 API, containing paginated monitor data. |
| `UserNoteDto.java` | A response object representing a user's note. |
| `UserProfileDto.java` | A response object containing a user's public profile information. |
| `analytics/ActivityStatsDto.java` | DTO for activity statistics, such as new users and recordings over the last 30 days. |
| `analytics/AnalyticsOverviewDto.java` | DTO for high-level analytics data, including total users, recordings, and storage usage. |
| `analytics/ContentEngagementDto.java` | DTO for metrics on content engagement, like favorite counts for recordings. |
| `analytics/UserDistributionDto.java` | DTO for user distribution statistics, such as users by provider or role. |

## 3. Responsibilities & Logic Flow

**Key Responsibilities:**
- **Data Encapsulation:** DTOs encapsulate data for API requests and responses, providing a clear contract.
- **Validation:** They often include validation annotations (`@NotBlank`, `@Size`, etc.) to ensure data integrity at the controller level before it reaches the service layer.
- **Decoupling:** They decouple the internal domain models from the external API, allowing the data model to evolve independently of the public API contract.

**Example Workflow: User Registration**
1.  **Step 1:** An incoming `POST /api/auth/register` request is received by the `AuthController`. The request body is deserialized into a `RegistrationRequest.java` object.
2.  **Step 2:** The `RegistrationRequest` object is validated to ensure all fields meet the required constraints (e.g., email format, password length).
3.  **Step 3:** If validation is successful, the data from the DTO is passed to the `UserService` to create a new `User` entity.
4.  **Step 4:** The `AuthController` returns a `JwtAuthenticationResponse.java` containing an access token upon successful registration.

## 4. Dependencies

**Internal Dependencies:**
- **Controller Module:** This is the primary consumer of request DTOs and producer of response DTOs.
- **Model Module:** Some DTOs have factory methods (e.g., `fromModel()`) that convert domain model objects into DTOs for API responses.

**External Dependencies:**
- **Jakarta Bean Validation** (`jakarta.validation.constraints`): Used for declarative validation of DTO fields.
- **Jackson** (`com.fasterxml.jackson.annotation`): Used for controlling JSON serialization and deserialization.
- **Spring Framework:** While not a direct dependency of the DTOs themselves, they are fundamental to how Spring MVC handles web requests and responses.

## 5. Configuration

The DTO module itself does not require any specific application configuration or environment variables. The validation and serialization mechanisms that act upon DTOs are configured in other modules (e.g., `WebConfig` for Jackson, and Spring Boot's auto-configuration for validation).
