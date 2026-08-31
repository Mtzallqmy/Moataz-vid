# تقرير تنفيذ المرحلتين الرابعة والخامسة

## A–E: Local Speech

- أضيف `speech-core` و`speech-android` وwhisper.cpp JNI/CMake المثبت كـsubmodule.
- أضيف model installation/checksum/resume/leases، device RAM/thermal selection، PCM 16 kHz mono، chunking/overlap/checkpoints.
- أضيف word-level source timestamps، Room indexing، تطبيع/بحث عربي، packed transcript، silence/audio/duplicate/filler analysis، caption drafts وTimeline mapping.
- Room schema انتقل 1→2 مع migration غير مدمرة.
- اختبارات المصدر تغطي العربية والصمت وcaptions والربط الزمني؛ core smoke نجح Offline.

## F–K: AI Provider System

- أضيف unified LLM API، adapters لـOpenAI/OpenRouter/Hugging Face/NVIDIA/Custom، model discovery وtri-state capabilities.
- أضيف base URL/path normalization، auth modes، Keystore secret abstraction/implementation، HTTP/SSE/cancellation/retry/error taxonomy.
- أضيف structured strategies، tool loop، token budget، usage، model roles، routing/fallback، profile settings hooks.
- Room schema انتقل 2→3 مع provider profiles بلا أسرار وrole assignments.
- لا تعتمد الوحدة على Project/Timeline/Transcript، ولم تبدأ المرحلة السادسة.

## L: الاختبارات المنفذة

| الاختبار | النتيجة |
|---|---|
| Kotlin core smoke: model/storage/media/speech/provider | PASS |
| Arabic normalization + offline silence assertions | PASS |
| Base URL duplicate-v1 + secret redaction assertions | PASS |
| `git diff --check` | PASS |
| JUnit suites | مضافة إلى CI؛ تشمل transcript analysis وprovider core/fake HTTP/SSE/errors/capabilities/secrets |
| Android native build | يتطلب Android SDK/NDK وتهيئة submodule؛ لا يتوفر في runner المحلي الحالي |
| Live provider calls | لم تُشغل عمدًا؛ لا مفاتيح ولا تكلفة مطلوبة |

## الملفات الرئيسية

- `speech-core/`, `speech-android/`, `third_party/whisper.cpp`
- `ai-provider-core/`, `ai-provider-android/`
- `storage-room/.../SpeechEntities.kt`, `AiProviderEntities.kt`, `DatabaseMigrations.kt`
- `docs/stage-04-local-speech-transcript-analysis.md`
- `docs/stage-05-ai-provider-system.md`

## المخاطر والقرارات المتبقية

- قياس word timestamps على أجهزة Android فعلية ونماذج عربية متعددة قبل وسمها production quality.
- اختيار blob store مشفر/خاص في composition root وربطه بـKeystore.
- إضافة instrumented Room migration tests وnative ABI tests في Android CI منفصل.
- provider capability probes يجب أن تكون opt-in ورخيصة؛ UNKNOWN أكثر أمانًا من ادعاء دعم غير صحيح.
- المرحلة السادسة مسؤولة عن Privacy Policy وContext Builder وEditPlan schema/validation/simulation، وليست هذه الطبقة.
