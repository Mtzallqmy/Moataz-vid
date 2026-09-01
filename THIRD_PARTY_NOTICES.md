# Third-Party Notices

Moataz vid may use ideas, adapted rules, or portions derived from the following open-source project:

## video-use

- Source: https://github.com/browser-use/video-use
- Copyright © 2026 Browser Use
- License: MIT License

The original MIT license text is preserved in:

`assets/licenses/video-use-MIT.txt`

Moataz vid does not adopt video-use as its application architecture. Any reuse must remain traceable and comply with the original license notice.

## whisper.cpp

- Source: https://github.com/ggml-org/whisper.cpp
- Pinned revision: `eacbd8234c6654cdbf2c377f72b2106875479bdc`
- Copyright © 2023–2026 The ggml authors
- License: MIT License

The native speech runtime is included as the `third_party/whisper.cpp` Git submodule. The license text is preserved in `assets/licenses/whisper.cpp-MIT.txt`.

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

Moataz vid contains a typed `FfmpegNativeBridge` abstraction and an `FfmpegLicensePolicy`, but **no FFmpeg native binary is currently bundled in this repository**. Therefore no FFmpeg binary license is being represented as satisfied here.

Before a final distributable includes FFmpeg, its exact `configure` flags, enabled external libraries and resulting LGPL/GPL/nonfree obligations must be inspected from the actual build. The current V1 policy rejects `--enable-gpl`, `--enable-nonfree`, and explicitly rejects external `libx264`, `libx265` and `libfdk_aac` in the configured fallback build until a deliberate licensing decision is made.

## Fonts and creative assets

No proprietary font is introduced by stages 8–9. Any future bundled font, logo, LUT, music or other creative asset must carry distribution-compatible license metadata before it is shipped.
