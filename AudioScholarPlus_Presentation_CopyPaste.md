# AudioScholar+ Software Project Presentation

Copy-paste-ready slide text based on the provided presentation template, project proposal, SRS, and SDD.

---

## Slide 1 - Title Slide

AudioScholar+

Extending Multimodal Lecture AI with User-Controlled Prompt Configuration, Audio Quality and Speaker-Aware Traceability, and Multi-Source Audio-Video Merging

Team 5

Course: IT332

Adviser: [ADVISER NAME]

Date: [PRESENTATION DATE]

Visual Placeholder: [PLACEHOLDER: AudioScholar+ logo or clean title background]

---

## Slide 2 - Team Introduction

Team Members:

- Andre C. Policios
- Theodore Benjamin O. Narsico
- Denn Anton Marc B. Cayacap
- Dexter B. Dela Riarte
- Cydric Luis C. Racaza

Roles and Responsibilities:

- Project Manager: [MEMBER NAME]
- UI/UX Designer: [MEMBER NAME]
- Backend Developer: [MEMBER NAME]
- Frontend Developer: [MEMBER NAME]
- QA / Tester: [MEMBER NAME]
- Documentation Lead: [MEMBER NAME]

Visual Placeholder: [PLACEHOLDER: Team member photos or team role table]

---

## Slide 3 - Background of the Study / Project Context

- AudioScholar+ is an extension of the original AudioScholar lecture audio summarization system.
- The system supports students who rely on recorded lectures as study resources.
- Existing AI lecture summarization tools can reduce cognitive load, but fixed outputs and unclear audio reliability can limit usefulness.
- Students need more control over generated study materials and better awareness of unreliable lecture segments.

Visual Placeholder: [PLACEHOLDER: Current lecture-to-summary workflow diagram]

---

## Slide 4 - Problem Statement

Current Problems:

- Fixed-format summaries do not always match a student’s study purpose.
- Poor audio quality can lead to unclear, incomplete, or unreliable generated summaries.
- Students cannot easily identify which parts of a recording may need review.
- Multiple recordings from the same lecture are processed separately instead of being merged into one complete output.

Effects:

- Less flexible study material
- Lower confidence in AI-generated summaries
- More manual review work for students
- Incomplete learning outputs from fragmented recordings

Visual Placeholder: [PLACEHOLDER: Problem-and-effect diagram]

---

## Slide 5 - Stakeholders, Beneficiaries, and Users

Stakeholders:

- Students: main users who upload recordings and review generated study materials
- Instructors: indirect beneficiaries through improved student review and comprehension
- Project advisers and evaluators: assess system quality, usability, and technical completeness
- Development team: designs, builds, tests, and maintains the system

Main Benefit:

AudioScholar+ helps students transform lecture recordings into configurable, review-ready, and more transparent learning materials.

Visual Placeholder: [PLACEHOLDER: Stakeholder map]

---

## Slide 6 - Proposed Solution Overview

AudioScholar+ is a dual-platform Android and Web application that improves lecture recording summarization through:

- User-controlled output configuration before AI analysis
- Audio quality and noise reporting with timestamped warnings
- Speaker-aware traceability for clearer review of lecture segments
- Multi-source audio/video merging for unified and deduplicated summaries
- Usability evaluation support to measure user experience

Visual Placeholder: [PLACEHOLDER: High-level system architecture diagram]

---

## Slide 7 - SMART Goals Overview

AudioScholar+ objectives are:

- Specific: focused on configurable AI lecture summaries and reliability-aware review
- Measurable: evaluated through functional testing, usability testing, and requirement validation
- Achievable: built as an iterative extension of the existing AudioScholar concept
- Relevant: addresses student needs for flexible, transparent, and complete study outputs
- Time-bound: designed for development and evaluation within the project timeline

Visual Placeholder: [PLACEHOLDER: SMART goals overview diagram]

---

## Slide 8 - General Objective 1

General Objective:

Develop a user-controlled output configuration feature that allows students to define the type and structure of generated learning materials before AI analysis begins.

Specific Objectives:

- Allow students to choose an output type such as Notes, Study Material, or Review Material.
- Support configuration options for length, level of detail, and focus.
- Use selected preferences to guide the AI summarization process.
- Produce outputs that better match each student’s study purpose.

Visual Placeholder: [PLACEHOLDER: Output configuration screen mockup]

---

## Slide 9 - General Objective 2

General Objective:

Improve summary reliability by analyzing lecture recording quality and identifying unclear or risky segments.

Specific Objectives:

- Detect audio quality issues such as noise, clipping, missing audio, or unclear segments.
- Generate timestamped warnings for parts of the recording that may need review.
- Connect quality warnings to related summary sections when possible.
- Help students decide whether to re-record, supplement, or manually verify affected content.

Visual Placeholder: [PLACEHOLDER: Audio quality timeline or warning indicator mockup]

---

## Slide 10 - General Objective 3

General Objective:

Enable students to merge multiple audio or video sources from the same lecture into one unified and deduplicated summary.

Specific Objectives:

- Allow multiple recordings to be uploaded under one lecture context.
- Combine overlapping or fragmented lecture content.
- Remove duplicate information from merged summaries.
- Produce a more complete study output from multiple recording sources.

Visual Placeholder: [PLACEHOLDER: Multi-source merging workflow diagram]

---

## Slide 11 - General Objective 4

General Objective:

Evaluate the usability and learning support value of AudioScholar+ for student users.

Specific Objectives:

- Provide a post-task usability evaluation process.
- Measure ease of use, clarity, and usefulness of generated outputs.
- Collect feedback on configuration, quality warnings, and merged summaries.
- Use evaluation results to validate system effectiveness.

Visual Placeholder: [PLACEHOLDER: Usability evaluation flow or SUS-style form placeholder]

---

## Slide 12 - Scope and Limitations

Scope:

- Android and Web support
- Lecture audio or video upload and processing
- AI-generated configurable study outputs
- Recording quality and noise reporting
- Multi-source audio/video merging
- Usability evaluation support

Limitations:

- New summary generation requires internet access.
- The system does not guarantee that all AI-generated statements are factually correct.
- Audio quality warnings support review but do not fully repair poor recordings.
- The project focuses on academic lecture recordings, not all types of media content.

Visual Placeholder: [PLACEHOLDER: Scope and limitations table]

---

## Slide 13 - SRS Overview

Software Requirements Specification Summary:

Functional Requirements:

- User-controlled output type selection
- Lecture audio or video upload and processing
- AI-generated lecture summary
- Recording quality and noise reporting
- In-summary quality warning indicators
- Multi-source audio/video merging
- Post-task usability evaluation

Non-Functional Requirements:

- Performance
- Security
- Reliability
- Usability
- Maintainability

Visual Placeholder: [PLACEHOLDER: Requirement traceability matrix]

---

## Slide 14 - SDD Overview

Software Design Description Summary:

System Design Areas:

- Dual-platform Android and Web application structure
- Frontend interfaces for upload, configuration, summary review, and evaluation
- Backend processing for AI analysis, quality assessment, and merging
- Data storage for users, recordings, summaries, warning indicators, and evaluation results
- API structure connecting the frontend, backend, database, and AI services

Visual Placeholder: [PLACEHOLDER: SDD component overview diagram]

---

## Slide 15 - System Architecture

High-Level Architecture:

- Users access AudioScholar+ through Android or Web.
- The frontend handles recording upload, configuration, summary viewing, and evaluation.
- The backend manages processing requests, AI summarization, quality analysis, and merging.
- The database stores user records, lecture files, generated summaries, warning data, and evaluation responses.
- External AI services support transcription, summarization, and analysis tasks.

Visual Placeholder: [PLACEHOLDER: System architecture diagram]

---

## Slide 16 - Functional Requirements

Core Functional Requirements:

- The system shall allow users to upload lecture audio or video files.
- The system shall allow users to select the intended output type before analysis.
- The system shall generate AI-assisted learning materials from uploaded recordings.
- The system shall identify and report poor-quality recording segments.
- The system shall support merging multiple recordings into one unified summary.
- The system shall collect usability feedback after task completion.

Visual Placeholder: [PLACEHOLDER: Functional requirements table]

---

## Slide 17 - Non-Functional Requirements

Performance:

- The system should process lecture recordings within a reasonable waiting time.

Security:

- The system should protect uploaded recordings, generated summaries, and user data.

Reliability:

- The system should provide clear warnings when recording quality may affect output accuracy.

Usability:

- The system should provide a simple and understandable workflow for students.

Visual Placeholder: [PLACEHOLDER: Non-functional requirements dashboard]

---

## Slide 18 - Database / Data Flow Overview

Main Data Objects:

- User account
- Lecture recording
- Output configuration
- Generated summary
- Audio quality report
- Warning indicator
- Merged lecture output
- Usability evaluation response

Data Flow:

Upload recording → Configure output → Analyze audio quality → Generate transcript and summary → Display warnings → Save result → Collect evaluation feedback

Visual Placeholder: [PLACEHOLDER: Database ERD or data flow diagram]

---

## Slide 19 - UI / User Workflow Overview

User Workflow:

1. Student logs in or opens the application.
2. Student uploads one or more lecture recordings.
3. Student selects output type and configuration preferences.
4. System analyzes audio quality and processes the recording.
5. Student views the generated summary with warning indicators.
6. Student reviews, saves, or uses the generated study material.
7. Student completes the usability evaluation.

Visual Placeholder: [PLACEHOLDER: User workflow screenshots]

---

## Slide 20 - Testing and Evaluation

Testing Activities:

- Functional testing for upload, configuration, summarization, warnings, merging, and evaluation
- Interface testing for Android and Web workflows
- Data validation testing for saved summaries, warnings, and evaluation responses
- Usability testing with student users
- Requirement validation against the SRS and SDD

Expected Evaluation Focus:

- Ease of use
- Output usefulness
- Clarity of warning indicators
- Effectiveness of merged summaries

Visual Placeholder: [PLACEHOLDER: Testing matrix or evaluation results chart]

---

## Slide 21 - Expected Benefits

Expected Benefits:

- Students receive study materials that better match their learning purpose.
- Students gain clearer awareness of unreliable or unclear lecture segments.
- Multiple recordings can produce one more complete summary.
- AI-generated learning outputs become more transparent and review-ready.
- The system supports more flexible and efficient lecture review.

Visual Placeholder: [PLACEHOLDER: Before-and-after workflow comparison]

---

## Slide 22 - Conclusion

AudioScholar+ improves the original AudioScholar concept by making lecture AI outputs more configurable, transparent, and complete.

The system addresses key student needs through:

- User-controlled output configuration
- Audio quality and speaker-aware traceability
- Multi-source audio/video merging
- Usability evaluation support

AudioScholar+ aims to help students create more reliable, personalized, and review-ready learning materials from lecture recordings.

Visual Placeholder: [PLACEHOLDER: Final project logo or closing image]

---

## Optional Footer Text for All Slides

AudioScholar+ | Team 5 | IT332 | Slide [NUMBER]
