# Moataz vid — المرحلة الثانية

## Storage Architecture + Room + Project Files

**الحالة:** منفذة كأساس تخزين وعقود وRoom schema V1  
**النطاق:** لا Media engine ولا Whisper ولا AI providers ولا UI.

## 1. القرار النهائي

التصميم Hybrid:

- **Room 3** مصدر الحقيقة للبيانات المنظمة، Timeline الحالي، القيود، المراجع، وحركة التاريخ.
- **Transaction log** داخل Room يحفظ forward/inverse operations، مع revision/hash.
- **Snapshots دورية** ملفات مضغوطة فقط لتسريع الاستعادة والتعافي، وليست مصدر الحقيقة اليومي.
- **الوسائط والملفات المشتقة** تبقى ملفات، مع `FileReferenceEntity` داخل Room.
- **كل Timeline write** يمر عبر بوابة `RoomTransactionStore` ومعاملة كتابة واحدة.

البديل المرفوض: Timeline JSON كامل بعد كل حركة. السبب: write amplification، صعوبة العلاقات والاستعلام الجزئي، وخطر تضارب autosave. كما رُفض event sourcing الخالص لأنه يزيد تعقيد إعادة الفتح والمigrations في تطبيق Android محلي.

## 2. توزيع البيانات

| البيانات | Room | ملفات | قابلة للحذف |
|---|---:|---:|---:|
| Project/Sequence/Tracks/Clips | نعم | لا | لا |
| Transform/Speed/Effect params | JSON typed داخل rows | لا | لا |
| Captions | rows | لا | لا |
| Project constraints/protection | rows | لا | لا |
| Source/SAF references/fingerprints | rows | الأصل خارج/داخل التطبيق | لا |
| Transcript metadata | rows | artifact اختياري | artifact يعاد توليده |
| Proxy/thumbnail/waveform | metadata rows | نعم | نعم |
| Analysis | small JSON row | artifacts كبيرة | نعم حسب fingerprint |
| User assets | metadata | نعم | لا دون إذن |
| Generated assets | metadata | نعم | حسب provenance |
| Edit transactions/cursor | rows | snapshots دورية | log لا؛ snapshots القديمة نعم |
| Export history | rows | ناتج عبر MediaStore/SAF | لا يحذف تلقائيًا |
| Render/temp cache | metadata اختياري | نعم | نعم |

## 3. Room schema العملي

الجداول المنفذة في `storage-room`:

```text
projects, sequences, file_references, media_sources,
tracks, clips, clip_properties, captions, overlays,
effects, transitions, assets, project_constraints,
protected_ranges, transcripts, analysis_records, proxies,
edit_transactions, history_cursors, export_records
```

تم دمج تخصصات Clip قليلة الاستعلام في `clip_properties` بدل إنشاء جدول لكل audio/video/music subtype. هذا Hybrid normalized design يقلل joins، بينما يبقى `clips` خفيفًا ومفهرسًا زمنيًا.

### أهم القيود

- Project→Sequence→Track→Clip: `CASCADE`.
- MediaSource/FileReference المستخدم: `RESTRICT` لمنع فقد المرجع بصمت.
- `(sequenceId,type,orderIndex)` فريد للمسارات.
- `(trackId,timelineStartUs)` مفهرس.
- `(sequenceId,resultRevision)` فريد للمعاملات.
- `(sourceId,presetId)` فريد للـproxy.
- كل enum يخزن `TEXT` باسم ثابت، وليس ordinal.
- IDs `TEXT` من ULID مسبوق بنوع الكيان.

## 4. IDs

`UlidIdGenerator` ينتج IDs مستقلة عن filename/path/order، sortable زمنيًا، وتعمل Offline:

```text
prj_, src_, stm_, seq_, trk_, clp_, ast_, txn_, pln_, cst_
```

UUID كان بديلًا صالحًا، لكن ULID اختير لتسهيل ترتيب logs والتشخيص مع الاحتفاظ بعشوائية كافية. الـprefix ليس جزءًا من الصلاحية الأمنية ولا يستخدم لاشتقاق العلاقة.

## 5. Project directory

تحت app-private projects root:

```text
<projects-root>/<project-id>/
  project/                 # manifests/backups only
  media/                   # managed copies
  assets/
    user/                  # لا يحذف تلقائيًا
    generated/             # provenance محفوظ
  derived/
    proxies/
    thumbnails/
    waveforms/
    transcripts/
    analysis/
  cache/render/
  history/snapshots/
  exports/staging/         # قبل commit إلى MediaStore/SAF
  temp/
```

`ProjectPaths` يبني هذه المسارات من `ProjectId` صالح ويمنع path traversal. لا تحفظ مسارات مطلقة في Room؛ فقط URI أو relative path.

## 6. SAF وMediaStore

عند استيراد source:

1. `UriResolver.inspect(content://...)` يجلب الاسم وMIME والحجم دون real path.
2. يحاول `takePersistableUriPermission(READ)` إذا دعم provider ذلك.
3. المصدر الكبير يبقى Linked SAF افتراضيًا لتجنب النسخ.
4. ينسخ إلى app storage إذا كان الإذن غير persistable، أو طلب المستخدم portability، أو ثبت عدم استقرار provider.
5. عند فقد الإذن يصبح FileRef `PERMISSION_LOST` وSource offline؛ لا تحذف Clips.
6. Relink يفحص fingerprint قبل استبدال المرجع.
7. export يكتب إلى staging ثم commit إلى destination، ولا يفترض filesystem path.

## 7. Fingerprinting

Quick fingerprint V1:

```text
version + size + duration + mime + normalized media metadata
+ SHA-256(first sample + middle sample + last sample)
```

Full SHA-256 يحسب عند managed copy، relink غامض، أو طلب deduplication قوي. Partial hash يوازن القراءة مع ملفات 4K الكبيرة، لكنه ليس إثباتًا تشفيريًا للمساواة؛ عند التطابق الغامض نرقّي إلى full hash.

تغير fingerprint يجعل Proxy/Transcript/Analysis `STALE` قبل الاستخدام.

## 8. Autosave وcrash safety

- UI edits عالية التردد تُجمع افتراضيًا 250ms بواسطة `AutosaveCoordinator`.
- الأفعال الحرجة مثل Apply AI plan وproject close تستخدم `flush()` فوريًا.
- writer يطبق compare-and-swap على sequence revision داخل Room transaction.
- transaction وhistory cursor وتعديل Timeline يثبتون معًا أو يرجعون معًا.
- ملفات metadata الخارجية تكتب عبر `AtomicFileWriter`: temp في المجلد نفسه ثم atomic move عندما يدعمه filesystem.
- process death قبل commit يترك الحالة السابقة؛ بعد commit يجد cursor/revision حالة مكتملة.

## 9. Transactions وUndo/Redo

كل `EditCommand` يتحول إلى forward/inverse canonical operations. AI EditPlan بكل عملياتها Transaction واحدة:

```text
validate expected revision/hash
→ simulate
→ Room write transaction
→ mutate Timeline
→ compare-and-set revision
→ insert EditTransaction
→ update HistoryCursor
→ commit
```

Undo يطبق inverse بترتيب عكسي ويحرك cursor إلى parent. Redo يطبق forward للابن النشط. تعديل بعد Undo ينشئ branch جديدًا؛ التاريخ القديم لا يحذف فورًا.

## 10. CacheManager والسياسات

التصنيفات:

- Essential: database state، refs، constraints، user assets.
- Regeneratable: thumbnails، waveforms، proxies، analysis/render caches.
- Expensive regeneratable: proxy/analysis يمكن إبقاؤه أطول.
- Temporary: staging والتجارب غير المكتملة.

التنظيف LRU داخل كل فئة مع استبعاد pinned/in-use. في low storage:

1. temp/orphans.
2. render cache.
3. thumbnails/waveforms القديمة.
4. proxies غير المستخدمة.
5. expensive analysis أخيرًا.

لا يلمس user assets أو source refs. `CachePolicy` يخطط فقط؛ الحذف الفعلي يجب أن يراجع FileRef ويزيل metadata والملف كوحدة آمنة.

## 11. Storage pressure

- Warning الافتراضي: أقل من 2 GiB.
- Critical الافتراضي: أقل من 512 MiB.
- export headroom: `2 × estimated output + 256 MiB`.
- proxy يفشل مبكرًا إذا لم توجد مساحة output + working headroom.
- Android write failure لا يحدّث FileRef إلى READY.
- staging جزئي يعد orphan ويزال في startup cleanup بعد التأكد أنه ليس job نشطًا.

الأرقام defaults قابلة للضبط حسب الجهاز ولا تعتبر ضمانًا لحجم codec.

## 12. Migrations

- `MoatazVidDatabase.SCHEMA_VERSION = 1` و`exportSchema=true`.
- لا `fallbackToDestructiveMigration` في production.
- كل migration صريحة، transaction-safe، ومع fixture من schema السابق.
- Project package SemVer مستقل عن Room integer version.
- قبل migration كبيرة: فحص مساحة وbackup/checkpoint.
- مشروع من نسخة أحدث يفتح read-only أو يرفض بأمان، ولا يحفظ فوقه.

## 13. Kotlin components المنفذة

- `ProjectRepository`, `TimelineRepository`, `TransactionRepository`.
- `UriResolver` وtyped `StorageError`.
- `AutosaveCoordinator`.
- `ProjectPaths` و`AtomicFileWriter`.
- `CachePolicy` وstorage pressure/headroom.
- Room 3 entities وDAOs و`MoatazVidDatabase`.
- `RoomTransactionStore` كبوابة mutation واحدة.

## 14. الاختبارات

منفذ وقابل للتشغيل:

- half-open time ranges وrational FPS.
- IDs مستقلة ومسبوقة.
- project path containment.
- atomic file replace.
- cache cleanup يحترم pinned.
- autosave debounce/flush في JUnit source.
- smoke compile/run مستقل عن Gradle mirror.

مؤجل إلى Android SDK/CI:

- Room create/reopen وforeign keys.
- migration fixtures.
- SAF permission loss باستخدام instrumented fake provider.
- process death/reopen وlarge-project benchmark.

## 15. القيود الحالية

- لا يوجد Android SDK في بيئة التنفيذ الحالية، لذلك لم يُولد Room schema JSON من KSP بعد.
- لا يوجد حذف Project فعلي أو UI؛ توجد العقود والسياسات فقط.
- Transcript مجرد metadata/reference كما طلبت؛ لا Whisper.
- Snapshots strategy مثبتة ولم يوضع compressor بعد؛ تضاف عندما توجد معاملات فعلية من Editor Core.

