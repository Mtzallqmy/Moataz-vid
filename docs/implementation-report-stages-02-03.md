# تقرير تنفيذ المرحلتين الثانية والثالثة

## A) ما كان موجودًا

كان المستودع البعيد فارغًا. تم أولًا دفع README، AI Editor/EditPlan architecture، المرحلة الأولى Data Model، وإشعارات `video-use`. لم تكن توجد شيفرة Android أو Gradle أو اختبارات.

## B) المرحلة الثانية

- مشروع Gradle متعدد الوحدات دون UI.
- `core-model`: IDs، microsecond time، Rational FPS، Project/Source/Sequence/Track/TimelineItem.
- `storage-core`: repositories، URI abstraction، autosave، atomic writer، paths، fingerprint/cache/pressure، typed errors.
- `storage-room`: Room 3 schema V1، DAOs، علاقات وفهارس، transaction write gateway.
- Project directory وSAF/MediaStore وcrash/undo/migration policies موثقة.

## C) Schema والتخزين النهائي

Hybrid normalized Room + typed JSON extension fields + files. Timeline current state rows، history immutable transactions، snapshots ملفات دورية، derived media ملفات قابلة للتوليد. IDs ULID مسبوقة. لا raw filesystem path ولا media BLOB في Room.

## D) المرحلة الثالثة

- `media-engine`: RenderGraph، MediaEngine API، export/proxy/probe models، errors/progress/cancellation contracts.
- Capability resolver يختار Media3 أولًا ثم FFmpeg فقط عند الحاجة.
- typed FFmpeg bridge بلا Shell أو raw command/filter inputs.
- Media3 adapter specs/facades/engine boundary.
- codec/FPS/orientation/HDR/audio/overlay/proxy/export policies.

## E) MediaEngine وRenderGraph

Editor Core يصدر Graph immutable مربوطًا بـrevision. Adapter يحوله إلى Media3 Composition spec أو typed FFmpeg filter graph. Preview يستعمل الأصل أو proxy، بينما final export يحل دائمًا إلى المصدر الأصلي.

## F) الملفات

أضيفت وحدات:

- `core-model/`
- `storage-core/`
- `storage-room/`
- `media-engine/`
- `media3-adapter/`
- `tools/smoke-tests/`
- Gradle wrapper/config/version catalog.
- وثيقتا المرحلتين وهذا التقرير.

## G) الاختبارات

نجح `tools/smoke-tests/run.sh` بعد compilation بكوتلن 2.2.21:

```text
Moataz vid core smoke tests: PASS
```

Gradle dependency resolution تعذر في بيئة العمل بسبب وصول Java إلى mirror، لذلك استعمل compiler distribution مباشرة. Android/Room/Media3 instrumented tests لم تعمل هنا لعدم وجود Android SDK/device.

## H) القيود

- Android adapters تحتاج compile/fixture verification في Android Studio أو CI مجهز.
- Room generated schema JSON لم يولد بعد.
- FFmpeg native binary غير مثبت عمدًا.
- Export/preview الفعليان يحتاجان binding implementations وtest media.
- لا UI، Whisper، LLM providers، أو cloud.

## I) قرارات تحتاج موافقة

1. FFmpeg: اعتماد build upstream LGPL minimal وسياسة إتاحة source/relinking قبل التوزيع.
2. minSdk الحالي 26 وcompileSdk 36.
3. Room 3 بدل Room 2.x.
4. proxy default 720p وthresholds الحالية.
5. V1 export default MP4/H.264/AAC وHEVC اختياري.

## J) بداية المرحلتين الرابعة والخامسة — دون تنفيذ

المرحلة الرابعة يجب أن تبدأ بـEditor Core command model وvalidators/simulator وتطبيق EditPlan schema فوق `TimelineRepository` وRenderGraph compiler. المرحلة الخامسة تبدأ بالتحليل المحلي: probe، waveform، silence/scene indexes، transcript interfaces فقط، ثم Whisper بعد اعتماد الخصوصية والموارد.

