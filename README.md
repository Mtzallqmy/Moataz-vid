# Moataz vid

محرر فيديو ذكي يعمل محليًا على Android، يحوّل أوامر المستخدم الطبيعية إلى خطط تعديل منظمة وآمنة وقابلة للمعاينة والتراجع.

**المالك والمطور:** معتز العلقمي — تعز، اليمن  
**الحالة الحالية:** اكتمال الأساس المعماري ونموذج البيانات وعقود التخزين ومحرك الوسائط المحلي، قبل بناء الواجهات.

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
- [تقرير تنفيذ المرحلتين 2 و3](docs/implementation-report-stages-02-03.md)
- [إشعارات الأطراف الثالثة](THIRD_PARTY_NOTICES.md)

## حالة التنفيذ

- [x] المعمارية العامة وEditPlan V1.
- [x] المرحلة الأولى: نموذج Project/Timeline والكيانات والتخزين والتاريخ.
- [x] المرحلة الثانية: Storage Architecture + Room + Project Files foundation.
- [x] المرحلة الثالثة: Local Media Engine + Media3 + FFmpeg fallback contracts.
- [ ] المراحل اللاحقة للذكاء الاصطناعي والواجهات.

## الحقوق

Copyright © 2026 معتز العلقمي — تعز، اليمن. All rights reserved.

لا يمنح وجود الشيفرة في هذا المستودع ترخيصًا عامًا لاستخدام مشروع Moataz vid. تراخيص المكونات المقتبسة أو المعاد تصميمها موضحة في `THIRD_PARTY_NOTICES.md`.
