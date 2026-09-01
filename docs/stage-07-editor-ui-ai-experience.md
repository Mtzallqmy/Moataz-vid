# المرحلة السابعة — Editor UI + Timeline + AI Editing Experience

## النتيجة

أضيفت وحدتان: `editor-core` لحالة المحرر والأوامر والتزامن والاستعادة، و`editor-ui` لواجهة Compose Android. لا يصل Composable إلى DAO أو Media3؛ المسار هو UI → ViewModel → Controller/UseCase → Engine/Repository.

## تخطيط الشاشة

- الهاتف portrait: Preview ثم transport ثم toolbar وTimeline، مع AI/Transcript/Inspector كـbottom sheets قريبة من المحرر.
- الشاشات الكبيرة: Preview وTimeline بجانب panel بعرض ثابت.
- `CompositionLocalProvider` يفرض RTL للمحرر العربي، بينما timecodes وIDs تبقى نصوصًا رقمية مستقرة.

## Timeline

`TimelineViewportState` يحتفظ بـpixels/second وscroll وplayhead وvisible range وselection، مع الزمن الحقيقي `TimeUs` لا Float. zoom يحافظ على الزمن تحت نقطة اللمس ويُحصر بين 4 و2000 px/s. `TimelineVirtualizer` يعيد visible + overscan فقط، وCompose يستخدم lazy tracks/rows.

الفيديو يعرض thumbnail cache references، الصوت waveform cache، captions/overlay blocks بألوان واضحة. التحميل asynchronous عبر `ThumbnailRepository` و`WaveformRepository` مع debounce؛ لا decode أو DB query على UI thread/كل frame.

Playhead واحد مشتق من `EditorPlayer.state`. يدعم play/pause/seek، ويعيد viewport التوقيت الحالي. Preview الحقيقي يأتي عبر composition slot من Media3 adapter، والمعاينة الافتراضية تستخدم proxy وAUTO quality. `PreviewQualitySelector` يخفض الجودة للذاكرة/الحرارة/التعقيد.

## التحرير اليدوي

`ManualEditService` يقدم trim/split/delete/move/speed/gain وundo/redo كـtransactions ذرية. Trim drag يعدل transient state فقط، مع word snap ضمن 120ms، ثم commit واحد عند رفع الإصبع. handles لا تقل عن 24dp، وإعادة الترتيب تبدأ بـlong press ثم drop index صالح. القفل/المسار/المدة تمر عبر نفس validation الآمن.

## AI Chat

الحالات الظاهرة: idle/thinking/using tools/building plan/simulating/plan ready/applying/done/error/cancelled. لا chain-of-thought؛ تظهر حالة مثل «أجمع بيانات المشروع» و«أحاكي التعديلات» مع زر إلغاء.

`AiPlanCard` يعرض before→after وoperation count والملخص والتحذيرات وأزرار معاينة/تطبيق/رفض. المعاينة تشغل simulated project عبر player من دون commit. revise يبقي previous plan link. Apply يعطل المسار المتعارض منطقيًا وينتج Undo. Manual edit بعد الخطة يجعلها STALE ويعطل apply.

إذا لا يوجد provider يظهر إعداد مزود/الوظائف المحلية، ولا يتوقف manual editor. إذا لا يوجد transcript يظهر بدء التفريغ، بينما عمليات timeline/visual البسيطة تبقى متاحة. لا يدعي النظام Vision إذا لم تتوفر.

## Transcript وInspector

Transcript panel يعرض segments، highlights المقطع الحالي، ويبحث بالتطبيع العربي في الكلمات. النقر على نتيجة يحول Source Time إلى Timeline Time، يحدد clip ويطلب seek. Caption track موجود في Timeline، ومرحلة UI الحالية تعرض cue blocks وتترك تحرير preset typography المتقدم للمرحلة اللاحقة.

Inspector context-aware يعرض المصدر والمدة والسرعة والصوت وcrop/transform/color بحسب النوع. Import hook في `EditorProjectGateway` يستخدم SAF/MediaStore ثم probe/thumbnail/proxy/transcription دون إعادة تصميم التخزين.

## Autosave والوظائف والأخطاء

لا يوجد زر Save أساسي. الحالة Saving/Saved فقط. jobs مستقلة تعرض transcription/proxy/export/thumbnail/waveform progress. الأخطاء تستخدم keys ورسائل آمنة وrecovery action ولا تعرض stack trace.

`RestoredEditorState` يحفظ project/playhead/zoom/scroll/selection/pending ID. عند الاستعادة لا يُعاد streaming request الميت؛ الخطة لا تعود صالحة إلا لنفس revision.

## الاختبارات

- JVM: zoom/focal precision، large timeline virtualization، trim commit، split/delete/reorder، undo/redo، preview quality.
- Acceptance A–E: silence preview/apply/undo؛ best takes + duration؛ protected price؛ manual edit makes AI plan stale؛ manual editing without provider.
- Compose instrumentation: plan card Arabic actions، provider-missing actionability.
- CI يبني Android editor debug ويجمع Android test sources، بينما تشغيل الاختبارات الجهازية يحتاج emulator/device في pipeline مخصص.

## قيود المرحلة الحالية

- إعادة الترتيب V1 داخل المسار نفسه؛ نقل متعدد المسارات وauto-scroll المطول يحتاج تحسين gesture لاحقًا.
- Preview surface wiring وSAF launcher يتمان في app composition module عند إنشاء تطبيق التشغيل النهائي.
- تحرير caption timing/style الأساسي ممثل بالعقود والـInspector؛ محرر typography متقدم خارج النطاق.
- لم تُنفذ المرحلتان الثامنة أو التاسعة.

اعتمدت إصدارات Android المستقرة وفق وثائق [Compose BOM](https://developer.android.com/develop/ui/compose/bom)، [Activity](https://developer.android.com/jetpack/androidx/releases/activity)، و[AndroidX Test](https://developer.android.com/jetpack/androidx/releases/test).
