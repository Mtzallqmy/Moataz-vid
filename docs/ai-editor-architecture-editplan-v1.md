# Moataz vid — AI Editor Architecture & EditPlan V1

**التطبيق:** Moataz vid  
**المالك والمطور:** معتز العلقمي — تعز، اليمن  
**الإصدار المعماري:** AI Editor V1  
**الهدف:** محرر Android محلي يفهم أوامر المستخدم ويحولها إلى تعديلات آمنة وقابلة للتراجع على Timeline.

## 1. الفصل بين التخطيط والتنفيذ

الـLLM لا يحرر ملفات الفيديو. مسؤولياته محصورة في فهم الطلب، قراءة سياق محدود، تحليل النص والتوقيت والبيانات المحلية، اتخاذ القرار التحريري، واقتراح `EditPlan` منظم.

التنفيذ الفعلي محلي:

- Editor Core: مصدر حقيقة Timeline والمعاملات.
- Media3 وMediaCodec: التشغيل والتحويل والترميز الأساسي.
- OpenGL: الطبقات والمؤثرات المرئية عند الحاجة.
- FFmpeg: fallback معزول للعمليات غير المغطاة، وليس أداة مباشرة للـLLM.

```text
User Request → Conversation Engine → Context Builder → LLM
→ EditPlan JSON → Schema Validation → Semantic Validation
→ Timeline Simulation → User Preview → Apply Transaction
→ Timeline → Local Render
```

## 2. حدود الأمان

لا يملك الـLLM صلاحية تنفيذ أو اقتراح عمليات Schema من الأنواع التالية:

- Shell أو Bash أو أوامر النظام.
- أوامر FFmpeg الخام.
- حذف أو كتابة ملفات الجهاز.
- الوصول الحر إلى المسارات.
- HTTP requests أو تثبيت حزم.

الأداة الكتابية الأساسية الوحيدة هي `propose_edit_plan(EditPlanV1)`، وجميع أدوات المشروع الأخرى أدوات قراءة محددة.

## 3. Intents

| Intent | أمثلة |
|---|---|
| `EDIT_PROJECT` | احذف الصمت، اختصر الفيديو، احذف التكرار |
| `ANALYZE_PROJECT` | ماذا يوجد؟ ما أفضل لقطة؟ |
| `FIND_CONTENT` | أين ذكرت البطارية؟ |
| `CAPTION_EDIT` | أضف ترجمة، كلمتان في كل مرة |
| `AUDIO_EDIT` | اخفض الموسيقى، وضح الصوت |
| `VISUAL_EDIT` | Zoom، سطوع، 9:16 |
| `STRUCTURE_EDIT` | انقل الشرح، ابدأ بأفضل Hook |
| `EXPORT_REQUEST` | 1080p، 30fps، TikTok |

ليس كل طلب ينتج خطة تعديل؛ التحليل والبحث يعيدان نتيجة فقط ما لم يطلب المستخدم التغيير.

## 4. أدوات القراءة المعتمدة

| الأداة | الغرض |
|---|---|
| `get_project_info` | معلومات المشروع والمدة والدقة والمسارات |
| `get_sources` | مصادر الوسائط وmetadata |
| `get_timeline` | Timeline الحالي |
| `get_clip` | Clip محدد |
| `get_transcript_range` | نص بين وقتين |
| `search_transcript` | بحث داخل التفريغ |
| `get_silence_ranges` | مناطق الصمت المكتشفة محليًا |
| `get_word_boundaries` | حدود كلمات دقيقة للقص |
| `get_audio_analysis` | loudness/peaks/noise/speech confidence |
| `get_scene_boundaries` | تغيرات المشاهد |
| `get_visual_samples` | عينات صور محدودة بإذن Vision |
| `get_duplicate_segments` | مرشحو التكرار |
| `get_edit_history` | آخر التعديلات |

Context Builder يختار أقل مجموعة لازمة. الفيديو الخام لا يرسل افتراضيًا.

## 5. EditPlan V1

الشكل الأعلى:

```json
{
  "schemaVersion": "1.0",
  "planId": "pln_01J...",
  "projectId": "prj_01J...",
  "sequenceId": "seq_01J...",
  "requestId": "req_01J...",
  "baseTimelineRevision": 42,
  "baseTimelineHash": "sha256:...",
  "title": "اختصار الفيديو وإزالة التكرار",
  "summary": "إزالة الصمت الطويل والتكرار مع الحفاظ على شرح السعر.",
  "estimatedResult": {
    "currentDurationMs": 161000,
    "estimatedDurationMs": 47000
  },
  "operations": [],
  "warnings": [],
  "requiresUserApproval": true
}
```

حقول revision/hash تمنع تطبيق خطة قديمة على Timeline تغير بعد التخطيط.

## 6. Operations المعتمدة

| Operation | الوظيفة |
|---|---|
| `TRIM_CLIP` | تغيير بداية أو نهاية نافذة المصدر |
| `SPLIT_CLIP` | تقسيم Clip |
| `REMOVE_RANGE` | حذف نطاق محدد |
| `REMOVE_CLIP` | إزالة Clip كامل |
| `MOVE_CLIP` | نقل Clip بين مواضع/Tracks معتمدة |
| `REPLACE_WITH_TAKE` | استبدال Take بآخر |
| `CHANGE_SPEED` | تغيير السرعة ضمن الحدود |
| `SET_CROP` | ضبط crop/aspect ratio |
| `ADD_ZOOM` | إضافة حركة Zoom زمنية |
| `ADD_TEXT` | إضافة نص زمني |
| `ADD_CAPTIONS` | إنشاء Captions من transcript |
| `UPDATE_CAPTION_STYLE` | تحديث شكل وتقسيم Captions |
| `ADD_AUDIO` | إضافة موسيقى أو صوت Asset |
| `SET_AUDIO_GAIN` | تغيير gain |
| `ADD_FADE` | fade صوتي/مرئي مدعوم |
| `APPLY_COLOR_ADJUSTMENT` | brightness/contrast/saturation |

كل Operation يحمل IDs موجودة وتوقيتات محددة النظام. لا يوجد Operation عام لتنفيذ كود أو أمر.

## 7. التحقق

### Schema Validation

- IDs والأنواع والحقول معروفة.
- timestamps أعداد صحيحة و`start < end`.
- التوقيت داخل مدة المصدر/Clip.
- السرعة موجبة وضمن قدرات المحرك.
- لا إنشاء Track أو تعديل مصدر خارج العمليات المعتمدة.
- رفض الحقول الإضافية غير الموجودة في Schema.

### Semantic Validation

- Snap إلى حدود الكلمات ضمن tolerance معلنة.
- منع Clips شديدة القصر.
- منع overlaps غير القانونية.
- احترام ProtectedRanges وLocked Clips/Tracks.
- التحقق من Project Constraints.
- فحص captions والصوت بعد التغيير.

## 8. Protected content والقيود

ProtectedRange مستقل عن المحادثة ويحفظ مع المشروع. أمر مثل «لا تحذف السعر» ينشئ حماية في Source Time تمنع أي خطة لاحقة من قص المحتوى.

أمثلة Project Constraints:

- لا تستخدم موسيقى.
- حافظ على الشعار.
- الهدف 9:16.
- لا تحذف شرح السعر.

Hard constraint يمنع التطبيق، وSoft constraint يولد warning وموافقة، وPreference يوجه التخطيط.

## 9. Simulation وPendingTransaction

قبل لمس Timeline الحقيقي:

```text
EditPlan → clone state → apply virtually → validate result → report
```

المحاكاة تحسب المدة الجديدة، gaps، overlaps، المقاطع المحمية المفقودة، captions alignment، واستمرارية الصوت. الخطة الناجحة تصبح `PendingTransaction` وتعرض Preview/Details/Apply/Cancel. الفشل لا يغير المشروع.

## 10. Undo/Redo

كل خطة AI تطبق Transaction واحدة. تحفظ forward وinverse operations وrevision/hash قبل وبعد. Undo يعيد المشروع بالكامل إلى حالته السابقة، وRedo يعيد المعاملة كوحدة واحدة.

## 11. سياسات الأوامر التحريرية الأساسية

### حذف الصمت

التحليل محلي. السياسة الافتراضية المقترحة:

- `minimumSilenceMs = 500`
- `keepPaddingBeforeMs = 80`
- `keepPaddingAfterMs = 100`

لا يحذف كل sample صامت؛ الوقفات الطبيعية القصيرة تبقى، والوقفات العرضية الطويلة تختصر.

### حذف التكرار

يولد التحليل المحلي مرشحين بالتشابه، ثم يقارن الـLLM المعنى وجودة الإلقاء والسياق ويختار `REMOVE_CLIP` أو `REPLACE_WITH_TAKE`.

### أفضل Takes

تدخل في القرار: speech confidence، جودة الصوت والصورة، كفاءة المدة، الصمت، الأخطاء اللفظية، اكتمال المعنى، والسياق.

### Reel

Preset intent وليس Operation واحدة. يبني استراتيجية متكيفة مثل `HOOK → MAIN IDEA → BENEFIT → EXAMPLE → CTA`، مع مدة وشكل Canvas مستهدفين.

### اجعله أسرع

قد يعني حذف pauses/fillers/tails، اختيار Take أقصر، أو زيادة سرعة طفيفة؛ لا يساوي تلقائيًا `1.5x`.

## 12. Transcript وVision

- Raw transcript يحفظ word-level في Source Time.
- Packed transcript يولد للنموذج على شكل ranges قصيرة.
- القص الدقيق يستدعي word boundaries محليًا.
- Vision اختياري ويأخذ عينات frames محدودة بعد الإذن، لا يرفع الفيديو كاملًا.

## 13. الذاكرة والخصوصية

هناك Conversation Memory للأسلوب الحالي، وProject Constraints الدائمة للقرارات الملزمة.

مستويات الخصوصية:

1. `LOCAL_ONLY`: لا شيء يغادر الهاتف.
2. `TEXT_AI`: transcript/metadata/timing فقط.
3. `VISION_AI`: frames محددة بإذن المستخدم.
4. `CLOUD_MEDIA`: مستقبلي واختياري بالكامل.

## 14. اختيار المزود والـFallback

الأدوار المستقبلية: Planner، Fast، Vision، Local. يحدد ProviderRouter قدرات المهمة (`TEXT`, `STRUCTURED_OUTPUT`, `TOOLS`, `VISION`) قبل اختيار النموذج.

Structured output fallback:

1. JSON Schema.
2. JSON mode.
3. Plain JSON + parser + validator + repair.

حد Repair Loop هو 2–3 محاولات. نموذج text-only غير موثوق يسمح له بالتحليل والمحادثة دون تطبيق edits.

## 15. التكلفة والاستقرار

- packed transcripts وrelevant ranges فقط.
- local analysis وcaching قبل cloud calls.
- تقدير tokens/context/cost.
- فشل AI أو Whisper لا يعطل المونتاج اليدوي.
- JSON غير صالح لا يغير Timeline.
- فشل render لا يفقد Timeline.
- الذاكرة المنخفضة تستخدم proxies ومعاينة أقل دقة.

## 16. نطاق V1

يجب أن يتقن V1: حذف الصمت والتكرار والأخطاء، الاختصار، أفضل Takes، Hook، Reel، 9:16، captions، البحث عن جملة، حماية/حذف/نقل جزء، تسريع الإيقاع، Undo، شرح التعديلات، Preview ثم Apply، وتعديل خطة سابقة.

مراحل التطور:

- Level 0: Manual.
- Level 1: Assisted analysis.
- Level 2: AI commands.
- Level 3: AI Editor.
- Level 4: AI Director مستقبلًا.

## 17. الخلاصة

```text
LLM = المخرج والمخطط
Local Analysis = مساعد المونتير
Editor Core = Timeline ومصدر الحقيقة
Media3 / FFmpeg = منفذ محلي
Whisper = السمع مستقبلًا
Vision Model = النظر الاختياري
EditPlan = العقد الآمن بين الذكاء والمونتاج
```

## 18. حقوق المشروع والمصادر

Moataz vid — Copyright © 2026 معتز العلقمي — تعز، اليمن. All rights reserved.

استفاد التصميم من أفكار وقواعد في مشروع `video-use` المرخص MIT دون اعتماد معماريته أو نسخها حرفيًا. الإشعار الكامل موجود في `THIRD_PARTY_NOTICES.md` و`assets/licenses/video-use-MIT.txt`.

