# Moataz vid

محرر فيديو ذكي يعمل محليًا على Android، يحوّل أوامر المستخدم الطبيعية إلى خطط تعديل منظمة وآمنة وقابلة للمعاينة والتراجع.

**المالك والمطور:** معتز العلقمي — تعز، اليمن  
**الحالة الحالية:** المرحلتان 8 و9 (الإنتاج الإبداعي والتصدير) والمرحلة 10 (تطبيق Android الإنتاجي `:app`) منفّذة. CI يبني `assembleDebug` و`assembleRelease` عند تفعيل `-PincludeAndroidModules=true`.

## المبدأ المعماري

```text
User Request
  → AI Conversation Engine
  → Context Builder
  → LLM
  → EditPlan JSON
  → Validation
  → Timeline Simulation
  → User Preview
  → Apply Transaction
  → Local Timeline / Render
```

الـLLM يخطط فقط ولا يصل إلى Shell أو FFmpeg أو ملفات الجهاز. التنفيذ الفعلي محلي عبر Editor Core وMedia3 وMediaCodec وOpenGL، مع FFmpeg كخيار احتياطي معزول عند الحاجة.

## الوثائق

- [AI Editor Architecture & EditPlan V1](docs/ai-editor-architecture-editplan-v1.md)
- [المرحلة الأولى: Project / Timeline Data Model V1](docs/phase-01-project-timeline-data-model.md)
- [المرحلة الثانية: Storage Architecture](docs/stage-02-storage-architecture.md)
- [المرحلة الثالثة: Local Media Engine](docs/stage-03-local-media-engine.md)
- [المرحلة الرابعة: Local Speech + Transcript + Analysis](docs/stage-04-local-speech-transcript-analysis.md)
- [المرحلة الخامسة: AI Provider System](docs/stage-05-ai-provider-system.md)
- [المرحلة السادسة: AI Chat Editing Core](docs/stage-06-ai-chat-editing-core.md)
- [المرحلة السابعة: Editor UI + AI Experience](docs/stage-07-editor-ui-ai-experience.md)
- [تقرير تنفيذ المرحلتين 6 و7](docs/implementation-report-stages-06-07.md)
- [تقرير تنفيذ المرحلتين 4 و5](docs/implementation-report-stages-04-05.md)
- [تقرير تنفيذ المرحلتين 2 و3](docs/implementation-report-stages-02-03.md)
- [إشعارات الأطراف الثالثة](THIRD_PARTY_NOTICES.md)

## حالة التنفيذ

- [x] المعمارية العامة وEditPlan V1.
- [x] المرحلة الأولى: نموذج Project/Timeline والكيانات والتخزين والتاريخ.
- [x] المرحلة الثانية: Storage Architecture + Room + Project Files foundation.
- [x] المرحلة الثالثة: Local Media Engine + Media3 + FFmpeg fallback contracts.
- [x] المرحلة الرابعة: Local Whisper + Transcript + Analysis foundation.
- [x] المرحلة الخامسة: Unified AI Provider System foundation.
- [x] المرحلة السادسة: Chat Editing وContext وEditPlan validation/simulation/apply.
- [x] المرحلة السابعة: Editor UI وTimeline يدوي وتجربة AI pending plans.
- [x] المرحلة الثامنة: الإنتاج الإبداعي (captions، overlays، transitions، audio automation) على Media3.
- [x] المرحلة التاسعة: التصدير والـproxies وقدرات الـcodec وWorkManager ومسارات الإخراج المحققة.
- [x] المرحلة العاشرة: تطبيق Android الإنتاجي (`:app`) مع شاشة التحرير والتخزين والتصدير؛ `assembleDebug` / `assembleRelease` / `lintDebug`.

## الحقوق

Copyright © 2026 معتز العلقمي — تعز، اليمن. All rights reserved.

لا يمنح وجود الشيفرة في هذا المستودع ترخيصًا عامًا لاستخدام مشروع Moataz vid. تراخيص المكونات المقتبسة أو المعاد تصميمها موضحة في `THIRD_PARTY_NOTICES.md`.
