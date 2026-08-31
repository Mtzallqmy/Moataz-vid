# Moataz vid — المرحلة الثالثة

## Local Media Engine: Media3 + FFmpeg Fallback

**الحالة:** عقود RenderGraph/MediaEngine والسياسات وMedia3 adapter boundary منفذة.  
**النطاق:** لا codecs native bundled ولا UI ولا cloud processing.

## 1. المعمارية

```text
Editor Core
  → RenderGraph (immutable, revision-bound)
  → CapabilityResolver
  → MediaOperationPlanner
     ├─ Media3Engine (primary)
     └─ FfmpegFallbackEngine boundary
  → Preview / Proxy / Export
```

Timeline/Room لا يمران إلى Media3 مباشرة. `RenderGraph` هو العقد المحايد ويحتوي source windows، placement، tracks، transform/crop/rotation، opacity، speed، gain/fades، overlays، effects، canvas/FPS/HDR، transitions، وaudio layers.

## 2. MediaEngine API

العقد المنفذ يدعم:

- `probeMedia`
- `prepareProject`
- `preparePreview` / `updatePreview` / `seek`
- `renderPreviewRange`
- `export` / `estimateExport`
- `generateProxy` / `generateThumbnail`
- `cancel`
- `observeProgress`
- `getCapabilities`

النتائج `MediaResult<T>` والأخطاء typed؛ لا exceptions خام إلى UI.

## 3. RenderGraph

العقد الأساسي:

- `OutputCanvas`: width/height، Rational FPS، ProjectColorMode.
- `VideoLayer`: input، source range، timeline placement، transform، opacity، speed، effects.
- `AudioLayer`: origin، range، gain/pan/mute، preserve pitch، fades، role.
- `OverlayNode`: text/caption/image بتوقيت وتحويل.
- `TransitionNode`.
- `MediaInput.Original/Proxy/Asset`؛ لا path أو URI.

Graph يحمل `timelineRevision` لضمان أن preview/export يعودان للحالة الصحيحة.

## 4. Media3 mapping

`Media3CompositionMapper` يحول Graph إلى `Media3CompositionSpec` مستقرة، ثم Android binding يحولها إلى:

| Moataz vid | Media3 |
|---|---|
| Media input token | `MediaItem` |
| source range | clipping configuration |
| Video/Audio layer | `EditedMediaItemSequence` |
| clip edits | `EditedMediaItem` + Effects |
| كامل الخطة | `Composition` |
| preview | `CompositionPlayer` |
| export | `Transformer` |

تم فصل `TransformerFacade` و`CompositionPlayerFacade` حتى يمكن اختبار mapper وإدارة callbacks/cancellation دون تسريب Media3 إلى core.

Media3 هو primary لأنه يدعم Transformer/Composition، تسلسل audio/image/video، mixing، compositing، HDR، وCompositionPlayer للمعاينة وفق الوثائق الرسمية الحالية.

## 5. Preview

- CompositionPlayer يعرض Graph دون MP4 كامل.
- تغيير بسيط يستبدل composition/session state عند revision جديدة.
- proxy يستعمل للمعاينة فقط؛ export يعيد resolver إلى الأصل.
- WYSIWYG levels: exact، approximate، proxy color difference، أو placeholder لمؤثر غير مدعوم.
- effect لا يدعمه preview: يعرض placeholder/خفض جودة أو preview range cache، ولا يدعي التطابق.

## 6. Proxy workflow

الـproxy يولد تلقائيًا إذا تحقق أحدها:

- long edge > 1920.
- FPS أعلى تقريبًا من 30.
- HDR.
- bitrate أكبر من 25 Mbps.
- قرار low-memory/thermal policy.

الافتراضي `EDIT_720P`: H.264 hardware-friendly، bitrate 4 Mbps، SDR preview عند الحاجة، مع audio AAC منخفض/متوسط. Low-memory يستخدم 480p. Timestamp mapping يبقى Source Time نفسه؛ لا تُحوّل حدود القص إلى frames proxy، لذلك final export يعود للأصل بلا drift.

VFR proxy يخرج CFR على project FPS أو قيمة مناسبة، لكن source→timeline map يبقى microseconds.

## 7. FFmpeg fallback

لا raw command من التطبيق. العقد:

```text
RenderGraph
→ trusted planner
→ TypedFilterGraph
→ FfmpegExecutionRequest
→ native bridge argv/API
```

`TypedFilterGraph` يسمح nodes معروفة فقط: trim، scale، crop، setpts، volume، fades، overlay، crossfade. inputs/outputs resolver tokens، لا strings من المستخدم.

Fallback V1 المحتمل:

- variable speed غير المدعوم.
- transition معقدة.
- special caption burn-in.
- edge container/codec.
- filters مسجلة لا يدعمها Media3.

لا يستخدم fallback إذا كانت كل features في Media3 capability set.

## 8. FFmpeg packaging والترخيص

لا يعتمد المشروع FFmpegKit المؤرشف. المسار المعتمد قبل bundling:

- بناء native minimal من FFmpeg upstream أو vendor نشط ومدقق.
- LGPL build فقط، دون `--enable-gpl` أو `--enable-nonfree`.
- عدم تضمين `libx264`, `libx265`, `libfdk_aac` في V1.
- استعمال decoders/encoders المتاحة قانونيًا وتقنيًا، وتفضيل Android MediaCodec للـH.264/AAC.
- نشر build configuration، notices، source/offer وآلية relinking وفق مراجعة قانونية قبل التوزيع.

`FfmpegLicensePolicy` يفشل build/runtime verification إذا وجد GPL/nonfree أو مكتبات forbidden. القرار النهائي لتوزيع native binary يحتاج موافقة قانونية؛ abstraction تعمل دون تثبيت مكتبة مهجورة.

## 9. Codec strategy

V1 default:

```text
MP4 + H.264/AVC + AAC-LC + yuv420 + 48kHz
```

HEVC اختياري عند detector/device/user policy؛ AV1 مؤجل للقدرة. `CodecCapabilityDetector` يفحص decoder/encoder، max size/FPS، HDR، وoutput compatibility. لا تعتمد الخطة على اسم الجهاز.

## 10. FPS

- Rational في model (`30000/1001` لا `29.97f`).
- Canvas/project FPS هو canonical output cadence.
- source واحد وسياسة preserve يمكن أن تعتمد FPS المصدر.
- mixed sources تعاد sampling إلى project FPS.
- VFR يُقرأ بالتimestamps لا بافتراض frame ثابت.
- preview قد يخفض cadence حراريًا، export لا يغير target FPS دون warning.

## 11. Orientation

MediaProbe يفرق بين coded width/height وrotation metadata. RenderGraph transform يعمل في display-oriented space. لا نقلب width/height يدويًا ثم نطبق rotation ثانية. اختبارات Android يجب أن تغطي 90/270 وصور الهاتف portrait.

## 12. HDR

`ProjectColorMode`:

- `SDR`.
- `HDR_KEEP` إذا encoder/effects/display pipeline تدعم.
- `HDR_TO_SDR` عبر Media3 OpenGL tone mapping عند القدرة.

إذا لم يدعم الجهاز KEEP، لا يصدر ألوانًا محروقة: إما tone-map معلن أو typed `HdrUnsupported`. Proxy يمكن أن يكون SDR مع `PROXY_COLOR_DIFFERENCE`.

## 13. Audio

Graph يدعم source audio، music، voice، ambience وSFX، gain/pan/mute، constant speed مع preserve pitch، fades، ومزج عدة sequences. قبل export:

- حساب peak estimate.
- headroom/limiter extension point.
- clip prevention warning.
- loudness normalization interface مؤجل.
- noise reduction/AI audio خارج النطاق.

## 14. Captions وoverlays

- Basic text/caption/image: Media3 overlays/effects عند الدعم.
- layout معقد/Arabic shaping يجب أن يرستر محليًا إلى texture/bitmap عبر طبقة رسم موثوقة ثم overlay.
- OpenGL layer مناسب للمعاينة الديناميكية.
- FFmpeg fallback للـburn-in الخاص فقط، وليس افتراضيًا.

## 15. Export jobs

`ExportRequest` يحمل job id، Graph revision، output URI، settings. persistence في `ExportRecordEntity`:

- QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED.
- progress permille وtimestamps وbackend/error.
- Android binding يستخدم WorkManager للجدولة، وforeground media-processing service للعمليات الطويلة حسب قيود النظام.
- Transformer cancel/FFmpeg bridge cancel إلزاميان.
- process death: V1 يعيد job إلى retry/restart من البداية؛ resume من منتصف MP4 غير مضمون.
- output staging لا يصبح destination ناجحًا إلا بعد finalize.

## 16. Progress موحد

`MediaEngineProgress`:

- stage.
- percent nullable.
- processing FPS.
- processed duration.
- ETA nullable.

UI لا تعرف backend. Media3 callbacks وFFmpeg progress parser يتحولان إلى نفس model.

## 17. Error taxonomy

منفذ: UnsupportedCodec، DecoderFailure، EncoderFailure، MissingSource، PermissionLost، OutOfStorage، InvalidTimeline، Media3UnsupportedOperation، FfmpegFailure، ExportCancelled، OomRisk، HdrUnsupported.

لا تتضمن errors مسارات أو command lines أو logs حساسة. `safeLog` منقح ومحدود.

## 18. الأداء

- لا decode كامل 4K للthumbnails.
- reuse probes حسب fingerprint.
- proxies للـ4K/60/HDR/high bitrate.
- hardware codecs أولًا.
- preview resolution/cadence adaptive للحرارة والذاكرة.
- bounded frame queues وrelease surfaces/codecs عند cancellation.
- long videos تستخدم range reads وchunked cache indexes.

## 19. الاختبارات

اختبارات core/smoke المنفذة:

- Rational 29.97.
- 9:16 crop capability extraction.
- FFmpeg fallback decision.
- 4K60 proxy policy.
- FFmpeg license guard.
- typed graph invariants.

مصادر JUnit تغطي نفس العقود. Android fixtures المطلوبة لاحقًا: trim، concat، mixed orientation/FPS، no-audio، multi-audio، speed، crop، caption/image overlay، cancel، lost source، low storage، HDR، Media3 export وfallback bridge. تستخدم مقاطع قصيرة مولدة/مرخصة ولا تدخل ملفات ضخمة إلى Git.

## 20. Device matrix

| المستوى | API | SoC | عينات |
|---|---|---|---|
| Low | 26–28 | Snapdragon/MediaTek قديم | 720p/1080p AVC |
| Mid | 30–34 | Snapdragon/MediaTek متوسط | 1080p60، HEVC decode |
| High | 35–36+ | حديث | 4K60، HDR، HEVC encode |

يغطي portrait/landscape وrotation metadata وAVC/HEVC، مع قياس preview dropped frames، proxy time، export realtime factor، peak memory، thermal throttling.

## 21. القيود والمؤجل

- لم يُضمّن FFmpeg binary؛ يحتاج قرار ترخيص/build reproducibility.
- Media3 binding facades موجودة، لكن Transformer/CompositionPlayer callback implementation يحتاج Android SDK/device fixtures.
- variable speed وcomplex transitions fallback-only حاليًا.
- resume mid-export غير مضمون.
- advanced Arabic captions renderer وOpenGL shaders مرحلة لاحقة، دون بدء UI.

