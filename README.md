# Moataz vid

Moataz vid is an Android-native conversational video editor built around the load-bearing workflow and production-correctness principles of [`browser-use/video-use`](https://github.com/browser-use/video-use), adapted for an on-device Android runtime.

The implementation is intentionally **transcript-first, audio-first, strategy-gated, previewable, reversible, and self-verifying**. It does not run the upstream Python helpers inside the APK; their workflow and correctness invariants are ported into typed Kotlin modules backed by local Whisper, Room, Media3, MediaCodec and Android UI components.

## Production architecture

```text
Imported media
  │
  ├─► Local audio decode (MediaExtractor / MediaCodec)
  │      └─► whisper.cpp word-level transcript
  │             └─► cached Room transcript + word timestamps
  │                    └─► video-use packed phrase transcript
  │
  └─► Local timeline / thumbnails / waveform

User instruction
  │
  ├─► intent classification
  ├─► packed transcript + constraints + only-needed project context
  ├─► plain-language edit strategy
  ├─► explicit user strategy confirmation   ← required gate
  ├─► deterministic local planner or configured LLM planner
  ├─► EditPlan validation
  │      ├─ protected/locked/stale checks
  │      └─ video-use word-boundary / cut-padding checks
  ├─► timeline simulation
  ├─► user preview
  └─► atomic apply + undo/redo + persisted session memory

Export
  │
  ├─► RenderGraph
  ├─► video-use Media3 production preflight
  ├─► one final lossy encode with boundary fades and captions last
  ├─► structural output verification
  ├─► representative-frame + cut-boundary self-evaluation
  └─► publish destination only after verification passes
```

## video-use core port

The Android port is pinned for provenance to upstream `browser-use/video-use` revision:

`9575612f066aa517354790a645fd90f9f95a743b`

The dedicated `:video-use-core` module carries the reusable production policy and workflow contracts. The integration preserves the upstream load-bearing behavior:

- **Packed transcript is the primary AI reading view.** Phrase boundaries break on silence of at least 0.5 seconds or a speaker change.
- **Audio is primary.** Candidate cuts are reasoned from transcript word boundaries and silence before visual inspection is requested.
- **Ask → confirm → execute → iterate → persist.** Any edit that changes the timeline must pass an explicit plain-language strategy confirmation before planning or execution.
- **No cuts inside words.** Cut edges are validated against word timestamps and use the video-use 30–200 ms padding window.
- **30 ms boundary fades.** Android render policy enforces audio fades at clip boundaries to prevent pops.
- **Captions are composited last.** Media3 overlay ordering keeps captions above other overlays.
- **Output-timeline timing is authoritative.** Caption and edit timing is mapped to the rendered timeline rather than reusing source-local offsets.
- **Transcripts are cached per source fingerprint.** Re-transcription is invalidated only when source identity changes.
- **Self-evaluation happens before publish.** Export verification checks the output and samples representative frames plus both sides of edit boundaries before the SAF destination is committed.
- **Session decisions persist.** Strategy, plan and verification events are stored in Room as the Android equivalent of the upstream `project.md` session memory.

The exact FFmpeg mechanism used by the upstream helper (`extract → lossless concat → final overlays/subtitles`) is not copied literally because Android uses Media3/Transformer. The Android invariant is the same production goal: avoid repeated lossy generations and perform the final composition once. FFmpeg remains an isolated optional fallback contract; no FFmpeg native binary is bundled in the application.

## Main modules

| Module | Responsibility |
| --- | --- |
| `core-model` | Stable project, source, timeline and ID domain models |
| `storage-core` | Storage contracts and project snapshots |
| `storage-room` | Room persistence, migrations, histories, transcript cache and video-use session memory |
| `speech-core` | Word-level transcript domain, packed/search analysis, Whisper contracts |
| `speech-android` | whisper.cpp JNI + WorkManager execution |
| `video-use-core` | Android-native port of video-use workflow and hard production policies |
| `ai-provider-core` | Provider/model abstraction, structured output and tool contracts |
| `ai-provider-android` | Android secret storage integration |
| `ai-editor-core` | Intent, strategy gate, context building, planning, validation, simulation and transactions |
| `editor-core` | Editor controller, manual editing, preview state and timeline media cache contracts |
| `editor-ui` | RTL Compose editor UI, transcript and AI panels |
| `media-engine` | Backend-neutral RenderGraph and creative media domain |
| `media3-adapter` | Android Media3 preview/export, video-use render policy and output self-evaluation |
| `app` | Production composition root, Room repositories, local speech, provider settings and exporter |

## Local speech

Speech recognition is local. The application installs a multilingual Whisper model with a pinned SHA-256, decodes source audio through Android MediaCodec, converts it to mono 16 kHz PCM and executes the bundled whisper.cpp JNI bridge. Transcripts, segments and word timestamps are persisted in Room and reused by search, silence detection, best-take analysis, AI context and cut validation.

The application does not require ElevenLabs/Scribe to operate. The upstream video-use transcript semantics are adapted to the local Whisper runtime while retaining word-level editorial timing.

## AI providers

AI editing supports configured OpenAI, OpenRouter, Hugging Face Router, NVIDIA NIM and OpenAI-compatible/custom endpoints through one provider abstraction. API keys are encrypted with Android Keystore. Provider output can propose a typed `EditPlan`; it cannot execute shell commands, access arbitrary device files, or directly invoke FFmpeg.

Useful deterministic edits, including silence-oriented editing, remain local when no provider is configured. A provider is required only for open-ended language-model planning/analysis.

## Data safety and reversibility

- Imported project media is resolved only through repository-owned URIs.
- AI project/transcript fragments are marked as data and separated from the user instruction in prompts.
- Plans are validated against current project revision, locked items and protected ranges.
- AI and manual edits are committed atomically and persisted with undo/redo snapshots.
- Export is written to a temporary output target and published only after verification succeeds.
- Cleartext network traffic is disabled in the Android manifest.

## Database compatibility

The production Room database is currently version 5. Migrations include the stage-8 transition foreign-key repair (`3 → 4`) and the video-use session-memory table (`4 → 5`). The transition-table rebuild exists specifically to keep upgraded installations compatible with Room schema validation instead of relying only on fresh installs.

## CI production gates

`.github/workflows/ci.yml` is the release gate for this branch. A candidate is not considered production-ready until all jobs pass:

1. deterministic core + `video-use-core` unit tests;
2. Android debug/release APK build, release AAB build, debug/release lint and package/native-library audit;
3. x86_64 emulator boot, APK installation, real `MainActivity` launch with crash scan, and connected editor instrumentation tests.

Build artifacts include SHA-256 checksums and are uploaded only after the packaging audit succeeds.

## Building

Pure JVM/core verification:

```bash
./gradlew -PincludeAndroidModules=false \
  :core-model:test :storage-core:test :media-engine:test :speech-core:test \
  :video-use-core:test :ai-provider-core:test :ai-editor-core:test :editor-core:test
```

Android build:

```bash
./gradlew -PincludeAndroidModules=true \
  :app:assembleDebug :app:assembleRelease :app:bundleRelease \
  :app:lintDebug :app:lintRelease
```

Android modules are opt-in through `-PincludeAndroidModules=true`, so the deterministic core remains buildable on hosts that do not have the Android SDK.

## Release signing

The repository intentionally does **not** contain a private production signing key. CI can build and test the debug-signed APK plus an unsigned release APK/AAB. A store/distribution release must be signed with an externally managed stable Android keystore (for example through protected CI secrets). Never commit a production keystore or its passwords to this repository.

## Licensing and provenance

Moataz vid preserves third-party license notices in `THIRD_PARTY_NOTICES.md` and `app/src/main/assets/licenses/`. The video-use workflow port is derived from the MIT-licensed `browser-use/video-use` project; whisper.cpp is also MIT-licensed. See those files for the exact provenance and bundled-runtime status.
