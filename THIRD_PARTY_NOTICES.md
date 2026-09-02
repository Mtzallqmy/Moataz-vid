# Third-Party Notices

Moataz vid uses and adapts open-source components and ideas under their respective licenses. This file records provenance that is important to the production application.

## video-use

- Source: https://github.com/browser-use/video-use
- Android port pinned against upstream revision: `9575612f066aa517354790a645fd90f9f95a743b`
- Copyright © 2026 Browser Use
- License: MIT License

The original MIT license text is preserved in:

`app/src/main/assets/licenses/video-use-MIT.txt`

Moataz vid intentionally ports the **load-bearing video-use editing workflow and production-correctness invariants** into Android-native Kotlin. This includes transcript-first/audio-first reasoning, packed phrase transcripts, explicit strategy confirmation before execution, word-boundary-safe cuts and padding, boundary fades, captions-last composition, output-timeline timing, transcript caching, verification/self-evaluation before publication, and persistent session memory.

The Android application does **not** execute or bundle the upstream Python helpers as its runtime architecture. Equivalent behavior is implemented through typed Kotlin modules (`video-use-core`, `ai-editor-core`, `speech-core`, `media-engine`, `media3-adapter` and the production app integration) using local Whisper, Room, MediaCodec and Media3. The provenance remains traceable through this notice, source comments/tests and the pinned revision above.

## whisper.cpp

- Source: https://github.com/ggml-org/whisper.cpp
- Pinned revision: `eacbd8234c6654cdbf2c377f72b2106875479bdc`
- Copyright © 2023–2026 The ggml authors
- License: MIT License

The native speech runtime is included as the `third_party/whisper.cpp` Git submodule. The license text is preserved in `app/src/main/assets/licenses/whisper.cpp-MIT.txt`.

## AndroidX Media3

- Source: https://github.com/androidx/media
- Components used: Media3 Common, ExoPlayer, Transformer, Effect and UI
- License: Apache License 2.0

Media3 is consumed as a Maven dependency. Moataz vid does not claim ownership of AndroidX code. The application must preserve the notices required by the distributed AndroidX artifacts when producing the final binary.

## AndroidX / Jetpack libraries

The project also consumes AndroidX libraries including Room, WorkManager, Activity, Lifecycle, Compose and AndroidX Test through Maven dependencies. These components are distributed under their respective AndroidX licenses (predominantly Apache License 2.0). Their upstream notices remain authoritative.

## Kotlin and kotlinx libraries

Kotlin and kotlinx.coroutines / kotlinx.serialization are third-party dependencies governed by their upstream open-source licenses. Moataz vid does not claim ownership of them.

## FFmpeg status

Moataz vid contains a typed `FfmpegNativeBridge` abstraction and an `FfmpegLicensePolicy`, but **no FFmpeg native binary is bundled in this repository or APK**. Android production preview/export uses Media3/Transformer.

Before any distributable includes FFmpeg, its exact `configure` flags, enabled external libraries and resulting LGPL/GPL/nonfree obligations must be inspected from the actual build. The current V1 fallback policy rejects `--enable-gpl`, `--enable-nonfree`, and explicitly rejects external `libx264`, `libx265` and `libfdk_aac` until a deliberate licensing and distribution decision is made.

## Fonts and creative assets

No proprietary font, LUT, music or other third-party creative asset is knowingly bundled by the production core. Any future bundled font, logo, LUT, music or creative asset must carry distribution-compatible license metadata before it is shipped.
