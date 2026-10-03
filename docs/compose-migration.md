# Compose UI architecture and migration

The target is a Compose UI for all application screens, with no generated ViewBinding
or XML page/preference layouts. AndroidManifest.xml, strings, colors, drawable resources,
backup rules, and app-widget metadata are Android resources and remain valid in a Compose app.

## Package structure

Keep features together under `io.legado.app.ui`, with shared UI in explicit packages:

```text
base/
  BaseThemedActivity.kt         locale, window, background and platform behavior
  BaseComposeActivity.kt        Activity.setContent + app theme; no ViewBinding
  BaseComposeDialogFragment.kt  XML-free content, view-lifecycle disposal and existing window behavior
  BaseActivity.kt               legacy View host, removed at end of migration
ui/
  theme/LegadoComposeTheme.kt   app colors and Material 3 theme
  components/                  shared stateless composables
    LegadoTopAppBar.kt
    SettingsItem.kt
  navigation/MainDestination.kt  stable destination identities
  main/
    MainActivity.kt            Android platform callbacks and navigation effects
    MainRoute.kt               lifecycle-aware state collection and resource loading
    MainScreen.kt              state + event callbacks + content slot
    MainUiState.kt
    MainViewModel.kt            main state and existing application operations
    interop/LegacyMainPager.kt  temporary adapter for unmigrated destinations
  main/my/
    MyFragment.kt             transitional pager host for Compose content
    MyRoute.kt
    MyScreen.kt               My/More list, theme choices and customization dialog
    MyViewModel.kt            configuration observation, runtime state and draft
    MyMoreActivity.kt         Compose More screen; preserves ConfigActivity intent routing
    MySettingItem.kt          stable preference keys and display metadata
    MyNavigation.kt           platform actions shared by the two entry points
  welcome/
    WelcomeActivity.kt         startup/window/navigation effects
    WelcomeScreen.kt           static screen state and rendering
  autoTask/
    AutoTaskDebugActivity.kt    thin Android entry point
    AutoTaskDebugRoute.kt
    AutoTaskDebugScreen.kt
    AutoTaskDebugViewModel.kt
  association/
    OpenUrlConfirmActivity.kt  transparent Compose host, reuses restored dialog
    OpenUrlConfirmDialog.kt
    OpenUrlConfirmRoute.kt
    OpenUrlConfirmScreen.kt    external-link confirmation and source actions
    OpenUrlConfirmViewModel.kt restorable source-action confirmation and completion
    VerificationCodeActivity.kt transparent request-scoped Compose host
    VerificationCodeDialog.kt
    VerificationCodeRoute.kt
    VerificationCodeScreen.kt  captcha image/input and source actions
    VerificationCodeViewModel.kt restorable input, confirmation and completion
    VerificationCodeImageLoader.kt cancellable Glide request + independent preview file
  font/
    FontSelectDialog.kt        file picker, permissions and UI-thread callback
    FontSelectRoute.kt         lifecycle-aware pending selections and font preview
    FontSelectScreen.kt        font list, menus and system-font choice
    FontSelectViewModel.kt     cancellable listing/import and restored selection
  config/
    CoverRuleConfigDialog.kt   Compose host for cover-rule editing
    CoverRuleRoute.kt
    CoverRuleScreen.kt         form, validation and operation feedback
    CoverRuleViewModel.kt      restorable draft and cancellable operations
  video/config/
    SettingsDialog.kt         recreatable host; compatibility constructor retains no Context
    VideoSettingsRoute.kt
    VideoSettingsScreen.kt    settings and Compose speed-selection dialog
    VideoSettingsViewModel.kt  immediate preferences and separate speed draft
  book/read/
    ShadowEditDialog.kt        shadow parameter host, preserving parent callback
    ShadowEditRoute.kt         saveable half-step draft
    UnderlineEditDialog.kt     underline parameter host
    UnderlineEditRoute.kt      saveable width/distance draft
    HighlightParameterScreen.kt shared stateless parameter controls
  book/read/config/
    AutoReadDialog.kt          bottom-window host and idempotent dialog-count lease
    AutoReadRoute.kt           lifecycle-aware TTS update delivery
    AutoReadScreen.kt          speed and reader controls
    AutoReadViewModel.kt       restorable speed draft and pending TTS effect
    TipConfigDialog.kt         reader-info settings host and font callback
    TipSettingsRoute.kt
    TipSettingsScreen.kt       settings, templates, selectors and RGB/HEX editors
    TipSettingsViewModel.kt    immediate setting writes and restorable editor drafts
  book/manga/config/
    MangaEpaperDialog.kt       thin Compose host and reader preview callback
    MangaEpaperRoute.kt
    MangaEpaperScreen.kt       threshold slider and step controls
    MangaEpaperViewModel.kt    restorable threshold and dismissal persistence
    MangaColorFilterDialog.kt  thin Compose host and independent reader preview copies
    MangaColorFilterRoute.kt
    MangaColorFilterScreen.kt  brightness/R/G/B/A controls and numeric input
    MangaColorFilterViewModel.kt restorable immutable filter draft
    MangaFooterSettingDialog.kt thin Compose host for reader footer settings
    MangaFooterSettingsRoute.kt lifecycle-aware draft preview restoration
    MangaFooterSettingsScreen.kt visibility and alignment controls
    MangaFooterSettingsViewModel.kt restorable draft and dismissal persistence
  widget/dialog/
    WaitDialog.kt              lifecycle-aware ComponentDialog, preserves existing call API
    WaitDialogContent.kt       stateless Compose progress/message content
    PhotoDialog.kt             argument-compatible fullscreen Compose viewer
    photo/PhotoRoute.kt        cancellable image loading and animation lease
    photo/PhotoScreen.kt       saveable zoom/pan, gestures and drawing
    photo/PhotoImageLoader.kt  cached/local/remote loading off the UI thread
    photo/PhotoAnimatedPainter.kt lifecycle-managed animated Drawable drawing
  about/
    AboutActivity.kt           Android effects and existing application operations
    AboutScreen.kt             stateless screen using common settings components
    AppLogDialog.kt            thin Compose host and system share/text-viewer effects
    AppLogsRoute.kt
    AppLogsScreen.kt           live lazy list, confirmation dialog and export menu
    AppLogsViewModel.kt        restorable confirmation and pending detail/share state
    CrashLogsDialog.kt         thin Compose dialog host and text-viewer navigation
    CrashLogsRoute.kt
    CrashLogsScreen.kt         stateless lazy list and loading/error/empty feedback
    CrashLogsViewModel.kt      cancellable operations and pending log navigation
data/preferences/
  MangaEpaperPreferences.kt    existing e-ink preference key and IO reads
  MangaColorFilterRepository.kt legacy JSON compatibility and immutable snapshots
  MangaFooterSettingsRepository.kt footer JSON and independent reader event copies
data/repository/
  CrashLogsRepository.kt       local/backup crash-log I/O on Dispatchers.IO
  AppLogsRepository.kt         application/HTTP log details and export snapshots
  CoverRuleRepository.kt       cover-rule load/save/delete on Dispatchers.IO
  OpenUrlSourceRepository.kt   source disable/delete operations off the UI thread
  VerificationSourceRepository.kt captcha source actions on Dispatchers.IO
```

These are project conventions, not a package hierarchy mandated by Compose. Keep
existing `data`, `model`, and `help` functionality stable while migrating consumers.
Extract reusable data operations into repositories when feature boundaries justify
it. Do not create a domain layer or a Gradle module solely to wrap one existing call.

## Implementation rules

- Screens receive immutable values and event lambdas. They do not load preferences,
  access databases, start services, or obtain Activity/ViewModel instances.
- Routes connect screen state and Android effects. Collect StateFlow with
  `collectAsStateWithLifecycle`. Existing LiveData may be adapted during migration.
- ViewModels own changing screen state and screen operations; expose read-only flows.
  Store small restorable keys with SavedStateHandle, not Activity instances or bitmap data.
- Static screens can use simple screen state without a ViewModel.
- Use Material Surface to provide the theme background and content color;
  MaterialTheme alone does not set the text foreground for arbitrary containers.
- Keep UI state flowing down and events flowing up. Represent selection by stable
  destination identity, so configurable tab visibility cannot change what is selected.
- Keep file decoding, database queries and network requests off the main thread.
  Start composition-related effects with keyed effects rather than executing them in
  a composable body. Use viewModelScope for screen operations that survive recreation.
- Accept a root Modifier on reusable UI. Give interactive elements labels and roles;
  key lazy-list items by stable identity. Reuse the existing theme and translations.
- Use AndroidView only at documented platform/special-control boundaries, with correct
  lifecycle cleanup. Wrapping an XML page or RecyclerView is not a completed migration.
- Test screen behavior, restoration, filtering and operation cancellation, not just
  whether a composable exists.

## Current boundary

The main shell, welcome screen, automatic-task debug screen, and My/More screen
content no longer have XML layouts or ViewBinding. My/More also removes its Preference
XML and uses a preferences repository to retain existing backup-compatible keys.
The customization draft is saved separately from committed settings; Cancel does
not persist it or change services. Service observers run only while the destination
is resumed, and runtime status synchronization does not restart services. The existing About screen now shares theme, top bar and
settings components. The Compose host no longer inherits the ViewBinding host.

Crash-log listing and clearing now use Compose with a repository and StateFlow.
Local logs retain precedence over backup logs with the same filename. Clearing
refreshes the list even after partial failure; cancelled reads cannot open deleted
logs. Pending text-viewer navigation is acknowledged only after the resumed host
can show it. The shared TextDialog used to read log contents remains a legacy
consumer and will be migrated separately.

Application logs now expose immutable StateFlow snapshots with stable entry IDs;
existing Triple-based consumers and event notifications remain compatible. The
application-log dialog uses Compose for its list, clear confirmation and export
menu, and collects live updates only while resumed. Both application and HTTP
records are cleared after confirmation. Small exports retain text sharing; exports
over 64,000 characters use independent cache files written off the main thread.
Detail and share requests remain pending until a resumed host handles them. Text
selection and web links use Compose APIs. App-log details still use shared TextDialog.

WaitDialog now renders Compose progress and text without a layout or ViewBinding.
Its existing show/dismiss/setText API remains valid. Reusing the same dialog installs
a fresh Compose host for the new ComponentDialog lifecycle.

Cover-rule editing now uses Compose. Draft fields survive recreation independently
of persisted configuration. Cancellation and late loading results cannot overwrite
a draft or write after closing. Save/delete failures preserve the open draft; Cancel
does not persist it.

External-link confirmation and its transparent Activity now use Compose without
ViewBinding. The public URI, MIME and source arguments remain compatible. Source
mutations do not retain a Fragment callback. Recreation keeps one dialog and does
not finish the host; normal dismissal still finishes it.

Image verification and its transparent Activity now use Compose without XML UI or
ViewBinding. Input, confirmation and completion state survive recreation; request
results remain isolated by verificationResultKey. Configuration changes do not
cancel the request or finish the host. Glide waits and preview-file writes run on
IO with cancellation cleanup. Each enlarged preview uses an independent cache
file, so reloading cannot recycle a bitmap held by a restored PhotoDialog. The shared
PhotoDialog now uses the Compose viewer described below.

Manga e-ink threshold settings now use Compose with a preferences boundary,
immutable StateFlow, lifecycle-aware reader previews and SavedStateHandle drafts.
Closing without an edit preserves the stored threshold rather than writing the
old default of 150. Rotation does not persist the draft; delayed reads cannot
overwrite edits or write after dismissal. The dedicated XML layout is removed.

Manga color-filter settings now use Compose for brightness and RGBA controls,
retaining the 0–255 range, slider and single-step adjustments and adding numeric
input. Drafts survive recreation without early persistence. Reader callbacks
receive independent mutable copies of immutable UI state; late reads and repeat
dismissals cannot replace or resave a finished draft. The XML layout is removed.

Manga footer settings now use Compose for all seven hide options, footer
visibility and alignment. SavedStateHandle retains an immutable draft, preserving
legacy JSON fields and defaults. Reader events receive independent configuration
copies. Each RESUMED entry reapplies the current draft because the reader itself
reloads committed settings during recreation. Only real dismissal persists;
subsequent edits, preview requests and duplicate dismissal callbacks are ignored.
The dedicated XML layout is removed.

Video settings and the long-press speed picker now use Compose. Boolean settings
retain immediate writes to existing preference keys. The speed picker retains
0.5–6.0 in 0.1 increments, explicit confirmation/cancellation and a 3.0 default;
its uncommitted draft survives recreation. Fullscreen-on-start visibility follows
autoplay from the first composition. The DialogFragment has a no-argument
constructor and no retained Context. Its dedicated XML layout is removed.

Automatic reading controls now use Compose with the existing bottom-bar colors,
window behavior, catalog/menu/stop/page-animation actions and 1–120 second speed
range. Dragging updates a restorable draft; finishing persists and schedules TTS
rate updates only for a resumed host. A View-scoped, idempotent count lease avoids
double decrements, rejected-dialog decrements and stale host references across
recreation. Its dedicated XML layout is removed.

Highlight shadow and underline tuning now use Compose with saveable drafts and
shared half-step parameter controls. Radius, signed offsets, width and distance
retain their existing limits, formatting and 0.5 increments. Confirmation preserves
color and underline kind and delivers the parent callback once; cancellation does
not apply edits. Both dedicated XML layouts are removed.

Reader information settings now use Compose for title layout/font/weight/color,
chapter numbers, spacing, header/footer modes, six templates, text size and divider
colors. Selectors and RGB/HEX color and template editors are Compose. Placeholder
insertion preserves selection and IME composition; editor drafts survive recreation
without writes on cancellation. Immediate setting writes retain existing UP_CONFIG
payloads and external TIP_COLOR refresh does not overwrite open editor drafts.
Both dedicated layouts are removed; FontSelectDialog is migrated independently.

PhotoDialog now uses a pure Compose viewer with fit-center upscaling, delayed
single-tap dismissal, double-tap and pinch zoom, bounded panning and fling decay.
Zoom/pan survive saved-state restoration and same-source reloads. Existing
src/sourceOrigin/isBook arguments, cache precedence, local book images and fallback
artwork remain compatible. Static images use independent bitmap copies; animated
images retain a Glide lease and use a lifecycle-managed Compose painter, preserving
GIF playback. Animation callbacks stop before pooled resources are released. The
exclusive XML, PhotoView and its unused gesture helpers are removed.

Font selection now uses Compose for the font list, menu and system-font choice.
Private/external fonts retain path identity, Chinese-name sorting, typeface previews
and current-selection styling. Listing, import validation and bounded preview
caching run on IO. Directory permissions and file picker contracts remain intact;
selection callbacks run on the resumed UI thread without a ViewModel host reference.
Default-font inheritance remains independent of global system-font selection.
The exclusive adapter, two layouts and menu resource are removed.

Reader-menu customization now uses a Compose list with explicit primary/More groups.
Toggle, batch swipe selection, same-group drag reorder, accessibility move actions,
all/none and defaults retain the existing persistence contract. Unfinished gestures
restore their baseline after recreation; completed edits refresh the resumed reader.
The exclusive item layout and menu XML are removed.

Page-key editing now uses a Compose editor hosted by ComponentDialog. Previous/next
key drafts and focused input survive recreation; hardware down/up events are captured
before text input, while BACK/DEL retain platform behavior. Reset changes the draft
and confirmation persists both fields together. The exclusive layout is removed.

Sleep-timer selection now uses Compose with minute/chapter/episode presets and a
restorable custom editor. Bounds, custom history, exclusive timer-mode preferences
and parent-first service callbacks remain intact. Pending choices are consumed once;
a restored completed dialog closes without replaying its service callback.
The exclusive timer layout is removed.

Reader padding now uses Compose controls for header/body/footer offsets, linked
horizontal sides, divider visibility and confirmed region defaults. BODY changes
retain 150ms trailing windows with final/cancel/destroy flushing; reader refresh
payloads and shared/local style isolation remain intact. Window fading and the
view-scoped reader visibility lease are retained. The exclusive layout is removed.

Variable editing now uses a fullscreen Compose backdrop, multiline draft editor and
selectable comments with keyboard insets. Restorable drafts preserve key/title and
parent-first callbacks; only Save delivers a result, and restored completed dialogs
close without replaying it. The exclusive variable layout is removed.

Text-list dialogs now render immutable argument snapshots in a Compose LazyColumn
with collision-safe item identity, selectable text and existing web-link behavior.
Selection, scrolling and close remain available without a View adapter. Shared legacy
recycler/item resources remain until their other consumers migrate.

Read-aloud preferences now use Compose switches, a restorable start-mode picker and
navigation rows. Existing keys/defaults, focus-dependent call pausing, engine summary
and system settings navigation remain intact. Preference listeners live only while
resumed and running playback receives the original configuration-change event.
The exclusive preference XML is removed.

Number-picker dialogs now use Compose wheel and direct-input controls with the
existing fluent API, labels, decimal presentation, cyclic selection and neutral
button contract. Drafts survive recreation; confirmation returns the raw integer
once and cancellation delivers no result. The exclusive picker layout is removed.

The read-aloud control panel now uses Compose playback/chapter/paragraph controls,
rate and timer sliders, system-rate following and child-dialog entry points. Runtime
updates and engine summaries remain lifecycle-aware; queued host actions survive
recreation and are consumed once before delivery. Engine-name database access runs
on IO. The bottom reader visibility lease and exclusive-layout removal are retained.

Floating read-aloud controls now have Compose settings for six independent toggles,
width, opacity, hide threshold and reveal/reset-position actions. Legacy width
migration, numeric bounds, release-time persistence, draft flushing and settings
backup remain compatible. Preference observation follows the resumed lifecycle and
reader visibility counters release once per view. The preference XML is removed.

URL-option editing now uses a Compose form with restorable drafts, free-form method
and charset suggestions, WebView selection and all request/script fields. Existing
AnalyzeUrl setters retain JSON and retry/DNS parsing behavior. Confirm returns once;
cancel/backdrop returns no result. The exclusive URL-option layout is removed.

Highlight-note editing now uses Compose with restorable text/note drafts and explicit
save/delete/cancel actions. Editing does not mutate the live annotation before save.
Database writes run on IO and refresh reader state on Main; failures retain the draft
for retry. Existing annotation identity/style are preserved and completed restores
close without replaying persistence. The exclusive layout is removed.

Code-editor settings now use Compose with a font picker, immediate autocomplete
preview and six non-printable-character draft flags. User dismissal commits changed
flags once; configuration destruction preserves the draft without committing it.
Preferences stay behind a repository and previews are delivered only when resumed.
The exclusive editor-settings layout is removed.

Editor-theme selection now uses Compose radio choices for all eight existing
indices and automatic mode. Light/dark preferences retain their separate keys;
resumed hosts reapply the selected theme after recreation without extra writes.
The exclusive theme-picker layout is removed.

Text-selection menu customization now uses a Compose list with bar/More zones,
cross-zone drag reorder, transfer arrows, accessible move actions and defaults.
Only completed gestures persist; cancellation and recreation restore the baseline.
Existing JSON and expanded-menu migration remain compatible. The exclusive item
layout and menu XML are removed.

Reader-menu and text-selection-menu preference serialization/migration now belong
to the data layer. Remaining View entry points delegate to that implementation;
Compose repositories do not depend on UI helpers.

Speech-engine selection now uses Compose lists, system/HTTP choices, scoped apply,
login, import/export, deletion confirmation and share controls. Stable IDs and
restorable drafts preserve selection and editor contracts. Room/cache/file work runs
on IO; export preparation is cancellable and resumed platform actions consume their
queue entry before delivery. Cache deletion belongs to the ViewModel scope and its
completion toast does not replay service reset. Exclusive item/menu XML is removed.

Animated Drawable ownership is shared in data/image and the lifecycle painter in
ui/components/image. Photo retains a compatibility adapter; drawing callbacks stop
before Main-thread Glide release. New cover components can reuse the same contract
without making the data layer depend on dialog UI.

Bookshelf foundations now include immutable display snapshots and shared Compose
book/group cards and statistics headers. Standard, compact and grid presentations
retain metadata, read progress, unread/loading state, title modes and click/long-click
contracts. Cover rendering is an injected slot; existing shelf consumers remain until
their feature Routes connect these components.

The first bookshelf-page state foundation observes Room and preferences as cancellable
Flows, performs sorting/snapshot mapping off Main and exposes immutable entries.
Domain books remain private to the state holder for existing navigation/refresh APIs.
Group switches discard stale results and old refresh targets; parameters, goto-top
requests and lifecycle-bound update labels survive recreation.

The main shell still hosts bookshelf, discovery and RSS View fragments and the
Compose-content My fragment inside `LegacyMainPager`. The majority of the app is still unmigrated. Remaining
work includes these destinations, settings/preferences, search and book details,
source editors/import dialogs, RSS screens, file management, reading/audio/manga/PDF
controls, and all other dialogs. Completion must not be inferred from enabling
`buildFeatures.compose` or adding an AndroidView wrapper.

Replace destination internals with feature Routes before removing the pager. Once all
navigation destinations are composables, migrate navigation to a Compose navigation
host, remove obsolete Activity/Fragment entry points as appropriate, then remove
ViewBinding, legacy adapters and unreferenced page/preference XML. Preserve public
intent/deep-link contracts through the app entry point.

## Validation

```sh
./gradlew :app:compileAppDebugKotlin
./gradlew :app:testAppDebugUnitTest \
  --tests 'io.legado.app.ui.main.MainUiStateTest' \
  --tests 'io.legado.app.ui.autoTask.AutoTaskDebugOutputTest'
./gradlew :app:compileAppDebugAndroidTestKotlin
./gradlew :app:connectedAppDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.legado.app.ui.main.MainScreenTest
```

UI tests require a device/emulator. Also exercise launch delay zero/nonzero,
custom welcome backgrounds, night theme, e-ink mode, keyboard insets, bottom-bar skin
fallbacks, tab hiding, swipes, reselection, process recreation, and debug reruns on a device.
Compilation or JVM tests alone do not establish those runtime results.

Architecture references:
[Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations),
[Compose migration strategy](https://developer.android.com/develop/ui/compose/migrate/strategy).

Cover loading now uses an application-scoped repository with immutable source-aware
requests and configuration snapshots. Static images retain independent bitmap
ownership after Glide cleanup; animations retain explicit leases. Cover title
rasterization preserves horizontal/vertical typography, custom fonts, Unicode
characters, adaptive sizes and author labels without depending on a View.

Shared Compose cover components now display fixed 3:4 cropped covers and four-slot
group previews, with a 1200ms title fallback for slow requests. Animated drawing
follows the STARTED lifecycle and stops callbacks before releasing its Glide lease.
Legacy cover Views remain until all consumers have moved to these components.

Reader click-area configuration now uses a full-screen Compose nine-region grid
and a restorable action picker. Preference writes remain immediate, while actual
closure validates that a reader-menu area exists and recalculates click geometry.
Configuration changes preserve the picker and release only the current dialog lease.
The exclusive click-area XML layout is removed.

Dictionary-rule editing now uses a Compose form with a SavedState draft, selection
and full-code-editor target. Room reads and atomic rename/save transactions run
through a data repository. Failed loads block writes and offer retry; dirty exits
require confirmation, and stale paste/editor results cannot overwrite later edits.
The exclusive editor layout and menu XML are removed.

HTTP TTS editing now uses a Compose form backed by an IO repository and SavedState
drafts for every field, code-editor cursor and pending field target. Save/login
preserve the record identity and consume platform effects before dispatch. Empty
name drafts still receive dirty-exit protection, and failed initial reads block
writes until retry succeeds. The exclusive editor layout and menu are removed.

Style-1 book pages now use Compose lazy lists/grids, shared cover/cards, enabled-aware
pull refresh, stable book keys and an optional accessible fast-scroll rail. Saved
scroll positions survive the initial empty Room-loading phase. Fragment pager APIs
remain compatible, including group-only-update-read changes. Four old adapters and
the exclusive book-page layout are removed; style-2 shared item layouts remain.

Source-login forms now use Compose controls and a SavedState ViewModel for legacy
and v2 dynamic schemas, JS actions, field updates, debouncing, countdowns and header
commands. An observable source-initialization state protects restored dialogs from
reading partially loaded data, with visible failure and retry. Actual closure
persists legacy drafts and finishes the host; recreation does neither. Exclusive
login layout/menu XML and the old v2 View delegate are removed.

Remote-server configuration now uses a Compose WebDAV form with restorable field
selection and an IO repository. Saving preserves server identity and ordering with
one atomic Room replacement; failed loads block writes and support retry, while
failed saves retain the draft. The exclusive server-editor layout/menu are removed.

Remote-server selection now uses a keyed Compose list and lifecycle-aware Room
flow. Selection remains a SavedState draft until Apply or Default; cancellation
does not write preferences. Delete confirmation survives restoration, failures
remain visible, and editor/default/cancel callbacks preserve their host contracts.
The exclusive server-list item layout and menu are removed.

Style-1 bookshelf groups, toolbar, tabs, header and paging now use Compose. Each
group retains independent SavedState and scroll state, while only the active page
collects book updates and runs its age ticker in RESUMED. Group identity survives
reordering, header queries are skipped when disabled, and existing main-menu APIs
remain compatible with style 2. The exclusive style-1 parent layout is removed.

Dictionary-rule management now uses Compose lists, menus and modals with immutable
Room snapshots, stable selection and lifecycle-consumed sharing/import effects.
Range selection preserves ToggleAndReverse semantics. Drag cancellation, pause and
restoration revert unfinished selection/order changes; only completed reorders
write atomically. The old adapters/ViewModel and exclusive page/item/menu XML are
removed. Imports, export, complete JSON sharing and online history remain available.

HTTP TTS import now uses Compose selection and comparison lists, an IO parser and
AtomicFile sessions for complete JSON drafts. SavedState retains stable keys and
selection without large payloads. Code-edit requests are consumed in RESUMED, and
failed inserts retain drafts for retry; completion markers prevent replay after
restoration. Shared importer layouts remain for other consumers.

Dictionary-rule import now uses a Compose comparison list and stable code-editor
keys. Local matches remain unchecked by default, including changed payloads;
code renames recompute local status without resetting selection. Complete drafts
and completion markers live in AtomicFile sessions, with retry after write failure.
All rule fields and the existing importer/host contracts remain supported.

### Change-source word-count filter

Migrated mode selection and numeric range editing to a Compose screen with a SavedStateHandle ViewModel and a preferences repository. Both change-source hosts retain menu synchronization and refresh callbacks; disabling filtering preserves stored bounds. Invalid ranges and save failures keep the draft open, and completion callbacks are consumed once while resumed. Shared edit-field XML remains for other consumers. Validation includes 8 JVM state tests and compilation of 3 Compose interaction/restoration tests; device execution is unavailable.

### Bookshelf settings and transfers

Replaced bookshelf configuration, text/URL input, and add-progress dialogs with Compose screens, SavedStateHandle drafts, and preferences/data repositories. Settings preserve all legacy event payloads and field-specific refresh counts; cancellation does not write. Import picker group identity, pending exports, and stale-progress cancellation use explicit transfer state. File, network, and Room operations run on IO without retaining Fragment callbacks in ViewModels. The exclusive bookshelf configuration XML was removed. Validation adds 15 JVM tests and compiles 7 Android interaction/repository tests; device execution is unavailable.

### Dictionary lookup

Replaced dictionary lookup dialog layout and ViewModel with Compose tabs, lifecycle-collected state, and an IO repository. HTML results use a minimal platform WebView with JavaScript disabled, validated native action links, source-aware image loading, and explicit release/pause behavior. Each dictionary retains its own saved scroll position; query generations discard stale results. Added 17 JVM tests and compilation of 13 Android tests covering real WebView interactions, tab restoration, paused mounting, GIF bytes, and image options. No device execution is available. Removed the exclusive dictionary dialog XML.

### Replace-rule import

Migrated replace-rule import to Compose selection, code editing, and group editing with immutable JSON items and SavedStateHandle drafts. The IO repository preserves cached URL precedence, batched existing-ID lookup, legacy comparison/selection policy, grouping, and imported preview samples. Stable code keys preserve selection; cancelled group edits do not apply. Session files preserve drafts and completion markers. Added 14 JVM tests and compilation of 13 Android screen, route, dialog, and repository tests; no device execution is available. Removed the exclusive import menu; shared layouts remain for remaining consumers.

### TXT table-of-contents rule import

Migrated TXT rule import to Compose with immutable payloads, saved selection, per-item example expansion, and stable code-save callbacks. Existing IDs retain the legacy Existing/unchecked policy; full metadata and all input channels remain intact. Examples toggle between 3 and 39 lines without changing selection, and code saves update the original item. IO session files retain edited JSON and completion markers. Added 13 JVM tests and compilation of 12 Android screen, route, dialog, and repository tests; no device execution is available. Shared import layouts remain for their remaining consumers.

### Theme import

Migrated theme import to Compose with immutable JSON candidates, saved selection, stable code-edit callbacks, and IO session storage. Name matching and full configuration comparison preserve New/Update/Existing defaults. Import writes theme configurations through the existing API without applying a theme or changing current preferences. Added 11 JVM tests and compilation of 11 Android interaction, dialog, route, and repository tests, including comparison of every configuration field. No device execution is available; shared layouts remain for other consumers.

### RSS import data foundation

Added an immutable RSS import repository and injectable platform store before switching the existing page. Parsing, source-scoped replacement, original/replaced JSON, effective rule IDs, comparison, import preferences, grouping, and source metadata are preserved on an IO dispatcher. Room lookup batches avoid parameter limits; session files keep full drafts and completion markers. Added 9 JVM pipeline tests and compilation of 2 real Android store tests. The existing RSS UI is retained for the separate Compose page migration; device execution is unavailable.

### TXT rule editor

Replaced TXT rule editor layout/menu with Compose fields, code highlighting, explicit full-screen code editing, and a SavedStateHandle ViewModel. Paste changes editable fields while preserving IDs, order, and enabled metadata; focus, cursor, drafts, and one-time completion survive recreation. The repository awaits Room persistence before notifying the host, and checks existing-row presence inside the transaction so concurrent deletion cannot recreate the rule. Hosts now observe persisted changes rather than inserting a second time. Validation adds 21 JVM tests and compiles 11 Android interaction/Room tests; no device execution is available. Removed the exclusive editor XML and menu.

### Legacy bookshelf page restoration

Before the Compose bookshelf creates its view, restored legacy pager BooksFragment children are removed so FragmentManager cannot look up the retired pager container. Other child fragments, including open dialogs, are preserved. Validation compiles a real FragmentManager save/recreate cleanup test and runs the full JVM suite; no Android device execution is available.

### TXT rule management data and state foundation

Added a repository and saved management/selection ViewModel before switching the legacy hosts. Room mutations use current rows inside transactions, preserve unrelated metadata, and avoid restoring deleted rows. Management search, selection, deletion confirmation, ordering/gesture cancellation, precise picker selection, URL history, and sharing are represented as explicit state and effects. Validation adds 12 JVM behavior tests and compiles 5 real Android Room/file tests. Activity and selection-dialog UI migration remains separate; no device execution is available.

### Folder bookshelf (style 2)

Migrated the folder bookshelf to Compose with immutable projections, root mixed group/book content, all list/grid layouts, group cover previews, and shared toolbar/header components. Saved state retains group navigation and separate per-group list/grid scroll positions. Horizontal gestures support adjacent groups, cancellation and reversal; vertical scrolling and edge gestures remain independent. Repositories observe groups/books/settings and perform projections off the main thread, while resumed collection controls update labels. Added 9 JVM ViewModel tests plus behavior replacements for old View assertions and compilation of 8 Android screen/gesture/cover tests. Removed the obsolete adapters, header, folder page and seven item layouts, and toolbar menu XML. Device execution is unavailable.

### TXT rule management and selection pages

Switched management Activity and reader selection dialog to Compose search, menus, selection, drag ordering, slide selection, fast scrolling, import, export, and sharing. Stable IDs distinguish duplicate names; selection persists across picker search. Gesture cancellation/restoration does not persist unfinished edits. Default URL history can be removed from the current dialog and returns on reopening, matching the existing behavior. Added a JVM regression and compilation of 15 actual Compose tests plus an end-to-end share test that reads the chooser content URI and full JSON. Removed the obsolete Adapter/ViewModel, four exclusive layouts, and two menus. Other shared menu-test branches remain; no device execution is available.

### Reading style settings foundation

Added a preferences repository and saved ViewModel for reading style presets, sliders, font, conversion, weight, indent, page animation, and shared layout. UI refresh does not replay animation edits; only explicit animation selection reloads content. Saved effects preserve original reader event payloads and navigation order. Newly added presets are selected before background editing; restoring a finished dialog does not overwrite newer global settings. Added 8 JVM behavior tests and compilation of a real preferences checkpoint test covering both reading-mode selections and theme resource invalidation. The legacy screen remains until the separate Compose UI migration; Android device execution is unavailable.

### Reader background file and archive foundation

Added an I/O repository for background storage and reading-preset archive import/export. Export uses an immutable snapshot and a unique staging directory, preserves both fonts when filenames collide, closes streams, and cleans temporary files. Import returns detached preset JSON; background storage returns a filename, so file workers never apply the active preset. Serialized archive import protects the legacy shared staging reader. Added 3 JVM tests that inspect real ZIP entries and compilation of 3 Android file/export/import tests. UI integration follows separately; Android device execution is unavailable.

### Book group editor data and state foundation

Added an immutable group draft and saved ViewModel with loading, cover import, confirmation, and one-time completion states. Room edits re-read the current row and preserve ordering/visibility; concurrent deletion cannot recreate a group. New groups allocate an unused bit and clear stale book memberships in the same transaction. Deletion supports the legacy sign-bit group without affecting other memberships. Cover I/O uses application context and temporary files. Added 12 JVM behavior tests and compilation of 8 Android Room/file tests, including rollback and concurrent allocation. Compose UI follows separately; Android device execution is unavailable.

### Reading style Compose page

Replaced the reading style dialog and preset items with a Compose Screen/Route, preserving the native reader and font-selection contracts. Sliders, accessible increment controls, shared layout, page animation, conversion, font weight, indent, presets, and long-press editing use explicit saved state. Resumed effects preserve animation-before-event ordering and acknowledge navigation only when FragmentManager can open it. Detached bounded background previews do not mutate reader nine-patch state. Added compilation of 5 Compose screen tests, 3 real preference/event tests, 3 preview tests, and migrated the existing layout/font interaction tests. Removed the two exclusive XML layouts; JVM checks retain actual line-spacing and reader-position contracts. Android device execution is unavailable.

### Book group editor Compose page

Switched GroupEditDialog to Compose fields, seven sorting modes, refresh/read settings, cover preview/actions, delete confirmation, and native image selection. Saved drafts and pending selection survive recreation; persistence errors keep the draft open. Resumed completion closes once, and dismissal is disabled during a database mutation. Added compilation of 8 Compose interaction tests and a JVM capacity-error behavior regression replacing legacy source assertions. Removed the exclusive group editor XML; shared group-selection resources remain. Android device execution is unavailable.

### Book group management data and state foundation

Added a Room repository and saved management ViewModel observing user, system, and hidden groups. Visibility and ordering transactions use current rows and preserve other metadata; new rows remain appended and deleted rows are not recreated. Drag previews save a baseline, cancellation/restoration does not write unfinished ordering, and released ordering can retry after persistence errors. Add navigation and completion are explicit saved states; the 63-group limit counts positive user IDs only. Added 9 JVM behavior tests and compilation of 5 real Room tests. The management host switches separately; Android device execution is unavailable.

### Book group management Compose page

Switched GroupManageDialog to a stable-ID Compose list with visibility switches, editing, add, confirmation, drag ordering, edge scrolling, and accessibility move actions. Real pointer release persists ordering; cancellation, pause, disposal, and incomplete gesture restoration preserve the baseline without writing. System group labels retain their original suffixes, and Room updates retain full group metadata. Added compilation of 9 Compose behavior tests and removed the exclusive management item XML. Shared recycler layout/menu/ViewModel remain for the group picker until its separate migration. Android device execution is unavailable.

### RSS source import Compose page

Switched RSS import to immutable entry state, saved selection/search/group drafts, and Compose Screen/Route. Preserved source-scoped manual/automatic replacement, original and transformed code, effective rules, comments, preferences, full source metadata, and native child-dialog contracts. Large edited code is stored in an AtomicFile request repository; SavedState retains only its ID. Durable queued refreshes survive cancellation/recreation and drain after pending preference writes. Resumed effects consume before launching or synchronizing child dialogs. Added 12 JVM behavior tests, compilation of 10 new Compose/lifecycle/file tests, and migrated only RSS branches of existing filter/replacement/code tests. Removed the exclusive legacy RSS ViewModel; shared book-source resources remain. Android device execution is unavailable.

### Highlight rule import Compose page

Replaced highlight import with an I/O repository, immutable rows, saved selection, and Compose Screen/Route/Dialog. Strict raw-array/typed-envelope parsing, UUID comparison, default selections, complete rule metadata, and DAO ID/order merge remain covered by the existing parser tests. Large input sessions use AtomicFile storage; a completion marker prevents stale process restoration from repeating a completed import. Room persistence and the file marker are separate operations, so recovery still relies on idempotent DAO merging. Resumed refresh is consumed before notifying the reader and closing; cancellation writes nothing and empty selection disables confirmation. Added 8 JVM behavior tests and compilation of 10 new Compose/lifecycle/URI/Room tests; migrated only highlight branches of the shared file-import tests. Android device execution is unavailable.

### Book group picker data and state foundation

Added a saved group picker ViewModel and Room repository. Selection retains the full original bitmask, including hidden and legacy sign-bit groups; repeated checkbox changes use idempotent bitwise operations. Group deletion does not silently clear unshown selections. Confirmation preserves requestCode and consumes its result once; cancellation emits no result. Ordering transactions affect only selectable groups and preserve system metadata, with cancellation/restoration of unfinished gestures producing no writes. Added 10 JVM behavior tests and compilation of 4 real Room tests. Compose picker integration follows separately; Android device execution is unavailable.

### Book group picker Compose page

Switched GroupSelectDialog to a stable-ID Compose list with selection, add/edit, drag ordering, edge scrolling, and confirmation/cancellation. The original activity callback receives the full bitmask and requestCode once at RESUMED; a consumed finished state only closes. Native cancellation is disabled during persistence, and pause/disposal cancels unfinished gestures. Added compilation of 9 Compose interaction/lifecycle/restore tests. Removed the exclusive picker and item XML, the now-unused group menu, and the final legacy GroupViewModel. Shared recycler layout remains for other consumers; Android device execution is unavailable.

### Reader background/text settings state foundation

Added a preferences repository and saved background/text ViewModel covering preset/shared/global fields, colors, underline modes and spacing, review SVG validation/templates, archive import/export, and background selection. Picker and I/O operations capture their preset context and revision so late imports cannot overwrite newer edits; export retains the snapshot captured before selection. Pending effects keep original event payloads. Draft colors, cursors, template edits, and work can restore, while a finished dialog cannot overwrite newer global settings. Added 13 JVM behavior tests, including duplicate/canceled network-picker callbacks. Legacy UI remains for separate Compose integration; Android device execution is unavailable.

### Add book link Compose dialog

Replaced AddToBookshelfDialog with an I/O pipeline, saved immutable navigation result, and Compose Screen/Route. Existing shelf books bypass network; new links try explicit source options, enabled base URL, then ordered matching source patterns. Successful detail fetch stores full search-book metadata before opening book information; it does not directly insert into the shelf. Completion caching avoids repeating completed network work after stale process restoration; search persistence and the file marker remain separate operations. Resumed delivery consumes before navigation and closes synchronously even when the new Activity pauses the host. Added 16 JVM pipeline/state tests and compilation of 7 Compose/lifecycle/network/Room tests. Removed the exclusive XML layout; Android device execution is unavailable.

### Effective replacement data and state foundation

Added an I/O repository and saved ViewModel for the effective replacement list before switching its host. Real rule identity is separate from the synthetic Chinese-conversion item, so duplicate names and rule ID zero remain unambiguous. Disable waits for persistence, keeps rows on failure, avoids recreating concurrently deleted rules, and preserves removed chapter rows across restoration. Edit navigation and final refresh are consumed once; unfinished writes prevent dismissal. Added 8 JVM state tests and compilation of 2 real Room/preferences tests. The legacy dialog remains for separate Compose integration; Android device execution is unavailable.

### Review detail request and data foundation

Added a captured-context review repository and application store before switching the legacy dialog. Detail/reply requests use the captured book, chapter, source, paragraph data, page, and review ID, and reject late results after book/source/rule changes. JS review functions remain ahead of declarative rule checks. Immutable comment projections deduplicate main and reply rows while preserving original comment bodies and merging reply counts. Source-aware media resolution and fingerprinted AtomicFile snapshots run off the main thread. Added 10 JVM pipeline/merge/cancellation tests and compilation of a real JS/Room/media/cache Android test. Compose UI follows separately; Android device execution is unavailable.

### Effective replacement Compose dialog

Replaced the effective-rule recycler dialog with Compose rows, stable identities, conversion selection, progress, retry errors, and an explicit close action. Native rule editing retains its Activity result contract. Resumed navigation consumes saved effects before delivery; final source-import or reader refresh occurs once after a successful change. Busy database writes disable dismissal, while errors retain the affected row. Added compilation of 5 actual Compose interaction/restore tests, strengthened real Room full-field assertions, and migrated only the effective-rule branches of the existing source-replacement tests. Shared recycler/item resources remain for other consumers; Android device execution is unavailable.

### Chapter content editor data and state foundation

Added a captured chapter-target repository and saved editing ViewModel. Large text drafts live in AtomicFile checkpoints referenced by a small saved ID, with monotonic revisions protecting newer drafts from late writes. Existing BookHelp save fencing, content projection, reversal-marker invalidation, reset/refetch, literal/regex/case search, and title metadata remain preserved. Dirty cancellation awaits persistence and failures retain the draft; concurrent reset cannot overwrite newly typed text. Title transactions re-read current chapters and do not recreate deleted rows. Added 12 JVM behavior tests and compilation of 7 real disk/Room tests. Legacy UI remains for separate Compose integration; Android device execution is unavailable.

### Reader background/text Compose page

Switched BgTextConfigDialog and its color, restoration, underline-mode, SVG, and template editors to Compose Intent/Route/Screen. Preserved day/night/e-ink backgrounds, five public color IDs and exact event payloads, shared preset behavior, native file pickers, alpha completion, and all underline controls. Host effects deliver while resumed; detached background and SVG previews avoid changing reader resource ownership. Added compilation of 8 Compose interaction/pixel tests and 4 real preferences/event/parser tests. Replaced obsolete BG-specific source/XML assertions with actual data and behavior checks. Removed the two obsolete adapters and exclusive page/item XML; Android device execution is unavailable.

### Manual replacement picker data and state foundation

Added an I/O candidates repository and saved manual replacement ViewModel. Reader candidates retain disabled rules and exclude source-only rules; source candidates keep the original enabled source-scoped query. Selection filters missing IDs and confirms in candidate order. Confirmation is consumed once; cancellation leaves persistence to the host without writing. Range selection uses a baseline so reversed/canceled gestures restore prior choices and unfinished selection does not leak into saved state. Added 8 JVM state/recovery/gesture tests. The legacy dialog remains for separate Compose integration; Android device execution is unavailable.

### Manual replacement picker Compose page

Replaced ManualReplaceRulesDialog with a stable-ID Compose checkbox list, select-all controls, side-region range selection, reversal/cancellation, edge scrolling, and accessibility actions. Source selection preserves requestId and its callback; reader selection captures the original book URL before applying IDs, saving reader state, and refreshing. Resumed confirmation consumes once and cancellation writes nothing or changes no global replacement preference. Added compilation of 6 Compose gesture/lifecycle tests and 2 real Room candidate/order tests; migrated only Manual branches of the existing source-import tests. Shared recycler/item XML remains for other consumers; Android device execution is unavailable.

### Chapter content editor Compose page

Replaced the full-screen content editor with Compose text editing, search controls, match navigation/highlighting, plain/raw projection, copy, reset, title editing, and position scrolling. Native back waits for dirty auto-save; failures retain the page. Resumed callbacks reload only the captured reader target. Lifecycle checkpoint errors remain visible without crashing, and pending title reads restore before saving is enabled. Added 3 JVM regressions (15 editor behavior tests total), compilation of 11 actual Compose editing tests, and migrated only editor branches of reversal/lifecycle tests. Removed the exclusive layout and menu; device validation of predictive-back/IME combinations and very large text layout remains unavailable.

### Book memo data and state foundation

Added a Room repository and saved memo editor ViewModel. Memo observation remains independent of the editing draft; persistence waits for success before leaving edit mode, and failures retain input. Large drafts use revisioned AtomicFile storage with only an ID and cursors in SavedState. Completed/canceled edits checkpoint an empty inactive draft instead of retaining duplicate large content. Discard and clear are explicit confirmations; clear retains a monotonically dated empty memo so older backups cannot restore the text. Added 9 JVM behavior tests and compilation of 3 real Room/disk tests. Legacy Markdown UI remains for separate Compose integration; Android device execution is unavailable.

### Review detail Compose page

Replaced the review detail recycler with Compose comment/reply rows, resizing, embedded replies, paged loading, badges, source-aware images and photo navigation. Audio keeps a main-thread platform controller with released resources; media preparation and disk sessions remain in the captured-target repository. Saved effects wait for disk restoration and resumed delivery before consumption; concurrent reply caches serialize to retain all parents. Added 11 ViewModel behavior tests, 3 actual row-format tests, and compilation of 14 Compose/platform/host tests. Removed obsolete source-only assertions and the exclusive comment XML. Android device execution remains unavailable.

### Book source import data and state foundation

Added serialized immutable import entries, an I/O repository, durable request/session storage, and a saved import ViewModel. Preserve JSON/URI/URL/JavaScript input, all source metadata, scoped replacements and invalid-preview blocking, filtered selection, group preferences, original enabled/explore/custom order, and captured reader reimport delivery. Large edited code lives in AtomicFile requests rather than SavedState. Import publication remains noncancelable and completion is cached for recovery. Added 11 parser/pipeline and 15 state JVM tests plus compilation of 7 real Room/JavaScript/URI/disk tests. Legacy host remains for separate Compose integration; Android device execution is unavailable.

### Book memo Compose page and reusable Markdown

Replaced BookMemoDialog with a pure Compose half-height Route/Screen, portrait and landscape actions, editable selection/composition, keyboard focus, explicit discard/clear confirmation, and lifecycle draft checkpoints. Added immutable CommonMark/table projection and reusable selectable Compose Markdown with links, tables, lists, code, headings and lifecycle-owned static/animated images. Reader bottom-dialog visibility uses an idempotent lease. Kept existing real reader rotation/backup tests and added 5 Markdown JVM tests plus compilation of 10 new Compose tests. Removed the exclusive memo XML. Android device execution remains unavailable.

### Highlight style pure operations foundation

Added independent immutable style operations for all nine channels, color defaults and changes, fill and underline cycling, inherited font metrics, preset swatches, and the original setting bounds. The existing StyleHost retains live persistence ownership. Added 6 JVM behavior tests; legacy host remains for separate Compose integration.

### Highlight style Compose page

Replaced all highlight style content with Compose while retaining the existing bottom-sheet window and public StyleHost/color/font/editor contracts. Nine channels, presets, metrics, decoration tuning and number drafts use immutable state and resumed consumed effects. External host refresh updates state without creating callback loops; font invalidation remains on the main host. Added 9 JVM state tests and compilation of 8 actual Compose/host tests. Removed the exclusive style and channel XML; Android device execution remains unavailable.

### Code preview data and state foundation

Added revisioned private AtomicFile code drafts and saved preview state. Original and derived code remain separate; read-only previews and pending replacement/editor operations block writes, and source editor results publish the original once without closing the preview. Literal search runs on an injected compute dispatcher and ignores stale results. Saved state contains session IDs, cursors and small effects rather than large code. Added 12 JVM state tests and compilation of 3 real disk/backup tests. Legacy code dialog remains for separate Compose integration; Android device execution is unavailable.

### Book source import Compose page

Replaced ImportBookSourceDialog with stable-ID Compose selection, search and menus, grouped import settings, comments, replacement errors, and confirmation controls. Source code/manual/effective child callbacks retain their contracts; resumed publication updates only the captured reader book/source. Migrated only book-source branches in five existing integration suites, preserving RSS and native editor cases and real reader network/cache behavior. Added compilation of 12 new Compose/host tests. Removed the old import ViewModel; shared recycler/source item resources remain for active consumers. Android device execution is unavailable.

### Text and help dialog data/state foundation

Added background-staged private text requests, rich HTML/Markdown projection, and saved help state with directory selection, rendered literal search, offset-safe scroll requests, deadlines, and editor effects. Large content stays out of Bundle state; the model awaits request persistence before exposing it. HTML links, images, tables, lists, emphasis and CSS colors retain structured semantics, including functional RGB/alpha colors. Restored timers keep the original deadline and explicit close behavior. Added 6 projection and 10 ViewModel JVM tests plus compilation of 2 real request persistence tests. Legacy UI remains for separate Compose integration; Android device execution is unavailable.

### Curl conversion data and draft foundation

Moved the existing pure curl converter into the model layer without changing conversion logic. Added independent compute/I/O repository boundaries and revisioned private AtomicFile drafts with shared per-path locks, preserving direction detection and precise conversion errors. Large command input/output stays outside Bundle state and older writes cannot overwrite newer drafts. Initial seed content survives a failed first draft write so retry cannot consume an empty request. Added 6 repository JVM tests, retained existing converter coverage, and compilation of 2 real concurrent disk/recovery tests. Legacy host receives only the package import update; Compose integration follows separately. Android device execution is unavailable.

### Book source picker data and state foundation

Added an I/O Room repository and saved source picker state. Enabled source search retains DAO ordering and immutable name/group rows; selection re-reads the complete latest source before consumed callback delivery. Pending selection restores without bundling source JSON, cancellation ignores late results, and finished restoration only closes. Delay settings retain 0..9999 bounds, retryable writes, restored drafts, and protection against late preference reads. Added 8 JVM state tests plus compilation of 4 real Room/preferences tests. Legacy UI remains for separate Compose integration; Android device execution is unavailable.

### Book source picker Compose page

Replaced the full-screen picker with a stable-URL Compose list, live enabled-source search, name/group display, progress/retry states and a saved delay editor. Resumed selection consumes the complete source before the parent-first callback; finished restoration only closes. Native and Compose back preserve pending-write guards. Added compilation of 5 actual Compose/Fragment tests, including small-window scrolling, delay persistence, full source metadata and recreation. Removed the exclusive picker layout/menu; common text items remain for other consumers. Android device execution is unavailable.

### Curl conversion Compose page

Replaced the converter with Compose input/output, direction controls, conversion/copy/insert actions, selection/composition and a scrollable compact layout. Conversion ignores late results after edits, output remains selectable and read-only, and native insertion retains the parent-first asynchronous result contract. Captured side effects use disk payloads and resumed consumed delivery; restored delivered insertions are not automatically repeated. Added 10 JVM state tests plus compilation of 8 actual Compose/Fragment tests. Removed the exclusive converter layout/menu; Android device execution is unavailable.

### Font installation layer cleanup

Moved the shared file installer from the UI package into data/file and updated repository, cover-font, backup-restore and test imports. File validation, deduplication, conflict naming and synchronized installation behavior are unchanged. Existing 5 real installer/merge JVM tests and full Android test compilation validate the dependency change; data repositories no longer import UI helpers.

### Text/help Compose page and searchable rich text

Replaced TextDialog with Compose content, right-side help directory, rendered search and match navigation, deadline/auto-close handling, restored scroll and full-source editor navigation. Plain/Markdown/HTML constructors retain their behavior while Fragment arguments keep only a request ID. Reusable rich text renders links, colors, emphasis, tables and lifecycle-owned images with long-press preview and selectable text; actual text geometry drives search scrolling. Added compilation of 12 Compose/platform/Fragment tests and replaced three old source-only checks. Removed the exclusive text layout/menu; Android device execution is unavailable.

### Reader text action data foundation

Added value-only action/component snapshots and an I/O discovery/preferences repository. Existing primary/more menu configuration preserves action order and process-text applications; missing or failed application discovery keeps built-in actions. Component-based identities survive PackageManager reordering, while duplicate entries remain distinct. Speak-mode toggles serialize on the repository dispatcher. Added 7 JVM partition/discovery/thread tests and compilation of 1 actual PackageManager/resource test. Legacy popup remains for separate Compose integration; Android device execution is unavailable.

### Automatic task import data and state foundation

Added private disk import sessions and an I/O repository for JSON, URI and URL task imports. Full task configuration stays outside Bundle state; selection and editor receipts use small saved identifiers. Import preserves local ordering and latest run metadata, with persisted commit/refresh markers for retry. Repeated editor saves accept changed content and ignore successful identical replay; failed saves remain retryable. Cancellation ignores non-cooperative late loads. Added 11 JVM state tests and compilation of 5 real Room/session tests. Legacy UI remains for separate Compose integration; Android device execution is unavailable.

### Reader text action Compose popup

Replaced the reader action popup content with Compose primary/more partitions and accessible click/long-click actions. The native bridge preserves reader callbacks, resource IDs, all three positioning branches, platform copy/share/browser/process-text dispatch and finalization. Lifecycle owners and explicit composition disposal cover dismiss, detach and host destruction. Added 6 JVM state tests and compilation of 6 Compose/platform tests; replaced source-only positioning checks with boundary behavior tests. Removed two exclusive layouts and the exclusive menu XML. Android device execution is unavailable.

### Automatic task import Compose page

Replaced the import dialog with a stable-key Compose list, selection counts, import status, code editing, progress and retry controls. Parent-first CodeDialog callbacks retain request receipts across recreation and accept successive editor saves. Resumed navigation consumes the request before opening its child; busy work protects cancellation and confirmation. Added compilation of 4 Compose and 2 real Fragment restore tests, retaining unrelated storage/routing contracts. Shared import tests now address Compose row actions directly instead of removed layout IDs. Removed the old import ViewModel implementation and the last exclusive source-import item layout; shared recycler resources remain. Android device execution is unavailable.

### Search scope data foundation

Added an I/O repository and immutable source-name/URL projection for the search scope picker. Group choices retain enabled-source filtering; the source tab retains all sources, DAO ordering and name/group/URL/comment queries, including disabled sources. Added 4 JVM snapshot/flow/thread tests plus compilation of 1 actual Room filtering test. Legacy scope UI remains for separate Compose integration; Android device execution is unavailable.

### Update dialog data and saved state foundation

Added background private update requests and saved action tokens, keeping release notes and large metadata outside Bundle state. Formal and beta requests preserve primary/backup/mirror targets, browser priority and ignore-version rules. Rich release notes project on the compute dispatcher; ignore-version preferences use I/O and retain explicit retry on failure. Added 8 JVM state tests, converted two beta download source checks to actual state behavior, and compilation of 2 real disk/preferences tests. Native downloading/installing remains delegated to the existing service; Compose UI follows separately. Android device execution is unavailable.

### Code preview and editor Compose dialog

Replaced CodeDialog with selectable Compose code text, background syntax projection with identity UTF-16 offsets, search navigation, original/derived preview, a 48dp accessible position control and source-replacement actions. Native editor transfer uses background files and durable draft persistence before cleanup; cancellation after an editor result preserves recoverable content and prevents delivery from a cleared ViewModel. Public callbacks and request IDs retain parent-first behavior, including repeated editor-saved publication without closing. Expanded state coverage to 20 JVM tests plus 3 syntax tests, compiled 9 new Compose/host/disk tests and migrated CodeDialog branches in 9 existing integration suites. Removed the exclusive code preview layout; native full-screen editor resources remain. Android device execution is unavailable.

### Update Compose dialog

Replaced the update dialog with Compose rich release notes, version metadata and formal/beta actions. Resumed handoff retains the existing app-update download service and browser contracts, with explicit retry after a failed handoff; closing the dialog leaves service work alone. Legacy arguments upgrade to private disk requests and the scroll state is created after content loads to preserve its restored offset. Added compilation of 7 Compose and 2 real recreation tests; obsolete binding/XML assertions were removed while update caller lifecycle tests remain. Removed the exclusive update layout/menu; Android device execution is unavailable.

### Search scope Compose dialog

Replaced the search scope dialog with Compose group/source tabs, enabled-group choices, all-source filtering, preserved independent selections and accessible stable-key rows. Confirmation preserves group click order and the original single-source name/URL format; all sources and cancellation retain their callback semantics. Saved identifiers restore selections and pending results, lifecycle subscription stops with the view and resumed delivery consumes before the parent-first callback. Added 9 JVM state tests, an actual subscription lifecycle test and compilation of 6 Compose plus 2 real Room/Fragment recreation tests. Removed the exclusive scope layout/menu; Android device execution is unavailable.

### Shared rule syntax layer cleanup

Moved unchanged Legado/JSON/JavaScript regular expressions into the pure model/analyzeRule package. Compose dictionary, HTTP TTS, TXT TOC and code-preview syntax projections now share model rules without depending on native CodeView extensions. The remaining native editor uses the same definitions. Existing keyword and syntax projection JVM tests plus full Android test compilation validate the package/import change.

### RSS reading history data and saved state

Added an I/O history repository and immutable rows while preserving descending read-time order and distinct null/all versus empty-origin filters. Navigation saves a small record hash and re-reads current article metadata before host delivery. Clear confirmation uses a fresh count, restores by recounting and protects an active deletion; canceled and finished requests reject late reads. Added 8 JVM state tests and compilation of 3 real Room ordering/filter/deletion/metadata tests. Shared RSS sort APIs remain intact; Compose history UI follows separately. Android device execution is unavailable.

### Reader search menu Compose content

Replaced the reader search menu layout, controls and in/out animations with Compose while preserving its public reader bridge and callbacks. Immutable result snapshots keep current/previous navigation, clamp edge navigation safely and guard empty result lists. Completed exits deliver once, interrupted exits cannot hide a reopened panel, and the floating navigation remains available until the reader hides the host. Compose navigation-bar padding and reader colors preserve integration; detaching disposes composition and drops pending callbacks. Animation completions wait for a resumed host before dispatch. Added 5 JVM state tests and compilation of 4 actual Compose navigation/animation/lifecycle tests. Removed the exclusive search menu layout; Android device execution is unavailable.

### Change cover data and saved state foundation

Added immutable cover sessions and an I/O store retaining enabled cached covers, rule-first search, source concurrency/timeouts, strict first-result name/author checks and ordered deduplication. Stop, refresh and rule-result resume retain their distinct behavior; cancellation covers rule and source work. Revisioned AtomicFile sessions reject older writes. Selection persists its snapshot before publishing and restores by a small saved identity, keeping large/data image URLs outside Bundle state. Added 8 repository and 8 ViewModel JVM tests plus compilation of 2 real Room/session tests. Legacy UI remains for separate Compose integration; Android device execution is unavailable.

### RSS reading history Compose dialog

Replaced the history dialog with stable-key Compose rows, independent title/URL scrolling, browser links, progress/retry and fresh clear-count confirmation. Origin arguments and small pending keys survive recreation; resumed navigation re-reads complete latest article metadata and ignores canceled late results. Configuration recreation refreshes the history/count, loading blocks deletion, and cancellation invalidates a pending count. Expanded JVM state tests to 9 and compiled 9 Compose plus 3 real Fragment/filter/scroll/recreation tests. Removed the exclusive history item layout/menu and their two source-only menu checks; shared RSS sorting and recycler consumers remain. Android device execution is unavailable.

### Change cover Compose page

Replaced the full-screen cover picker with a stable-ID three-column Compose grid, progress/retry and distinct stop/refresh/resume actions. Shared ComposeCover preserves source metadata, explicit network-image behavior and the default-cover sentinel. Resumed selection waits for a durable receipt and consumes before the existing activity callback; canceled or destroyed hosts stop owned search work. Added compilation of 6 Compose/route tests and 2 real BookInfoEditActivity/Room/recreation tests. Removed the old picker ViewModel/Adapter and two exclusive layouts plus the menu; public book-info callers and cover APIs remain intact. Android device execution is unavailable.

### Automatic task management data and state

Added immutable task rows and an I/O management repository for field-preserving enable/cron updates, batched deletion, filtered-slot ordering, logs, URL history and export tickets. Existing initialization and scheduler refresh remain; runtime fields are excluded from exported tasks. Private revisioned drafts keep large import text outside Bundle state. Saved state retains visible-scope batch targets, hidden selections, reversible slide-selection baselines, confirmation drafts and consumed host effects. Added 13 JVM state tests and compilation of 6 real Room/batch/export/history/disk tests. Legacy management UI remains for separate Compose integration; Android device execution is unavailable.

### Audio speed and timer Compose popup

Replaced the audio slider popup content with Compose and accessible discrete controls. Speed preserves 0.5..3.0 in tenths, timer preserves 0..180 whole minutes, and programmatic refresh leaves playback unchanged. User changes retain the existing playback APIs, whose persistence/service behavior remains owned by AudioPlay. Native positioning and popup styling remain; view-tree owners and composition disposal cover dismissal, reopening and host destruction. Added 3 JVM normalization/dispatch tests and compilation of 2 Compose plus 1 actual native popup lifecycle test. Removed the exclusive slider layout; Android device execution is unavailable.

### RSS favorite configuration data and draft state

Added private revisioned favorite-configuration drafts and saved-state identifiers, retaining title/group editing and the existing reading/video host ownership of persistence. Blank edits fall back to original nullable fields, nonblank edits keep their exact text, and save/delete callbacks restore from disk before consumed delivery. Debounced autosave and lifecycle flush protect large drafts without placing their content in Bundle state. Added 8 JVM state tests plus compilation of 3 real AtomicFile/recovery tests. Legacy configuration UI remains for separate Compose integration; Android device execution is unavailable.

### Highlight rule editor data and saved draft

Added complete immutable rule/style drafts and I/O persistence, preserving hidden metadata, existing regex validation, scope/group policy and new-rule ordering. Revisioned AtomicFile sessions keep large patterns outside Bundle state; initial seed content stays available until persistence succeeds. Save records the stable UUID before Room insertion and stores a completion receipt, so retries retain the assigned record/order. Saved state retains only small session/event/color identifiers and consumed completion flags. Added 6 repository and 7 ViewModel JVM tests plus compilation of 2 real metadata/UUID/order/session tests. Legacy rule UI remains for separate Compose integration; Android device execution is unavailable.

### RSS favorite configuration Compose dialog

Replaced the configuration dialog with Compose title/group fields, selection/composition and focus recovery, IME/system-bar padding, scrollable compact content and save/delete/cancel controls. Its transparent full-screen bridge preserves outside-tap dismissal, both public constructors, legacy argument conversion and nullable/exact-text callback semantics. Resumed delivery consumes before the existing reading/video host callbacks; closing is guarded once. Added compilation of 7 Compose and 2 actual Fragment/draft/focus/selection recreation tests. Removed the exclusive favorite configuration layout; the separate favorites list remains for later migration. Android device execution is unavailable.

### Highlight rule editor Compose dialog

Replaced the full-screen rule editor with Compose fields, regex/body/title switches, scope choices and the existing style editor bridge. Complete metadata remains in the saved draft; saving preserves manual highlights. Color selection now uses a pure Compose palette, RGB/alpha controls and validated hex input; previews retain the shared highlight drawing rules. Resumed events consume before host delivery, and text/color drafts survive recreation. Added compilation of 6 Compose and 2 actual Fragment recreation tests, preserving existing real group/style integration coverage. Removed the exclusive editor layout and replaced source-only editor checks with behavior tests. Full JVM tests and Android test compilation validate integration; Android device execution is unavailable.

### Automatic task management Compose page

Replaced the task homepage with stable-key Compose rows, filtered selection, batch controls, reversible slide selection, cron/log/delete dialogs and import/export menus. Existing edit/debug/login/file/help contracts remain. Async payload delivery rechecks cancellation and resumed lifecycle; damaged receipts are consumed without blocking subsequent actions, and settled exports release temporary files even when a native launcher throws. Added compilation of 9 actual Compose behavior tests and 3 Activity/Room/native-intent/recreation integration tests. Removed the exclusive adapter, two layouts and two menus; shared tests retain non-homepage contracts. Full JVM tests and Android test compilation validate integration; Android device execution is unavailable.

### Bookmark editor data and saved state

Added immutable bookmark seeds and private revisioned AtomicFile drafts, preserving complete captured metadata and exact editable text. Small saved identifiers/selections restore drafts without placing large Parcelable content in Bundle state. Initial disk-write failures retain the seed for retry using the same UUID; committed receipts prevent repeat writes. Deletion retains edit-position permission and cancellation leaves Room unchanged. Late noncooperative reads/commits check cancellation before publishing UI state. Added 10 ViewModel JVM tests and compilation of 4 real Room/disk/failed-first-write recovery tests. Legacy UI remains for separate Compose integration; Android device execution is unavailable.

### Bookmark editor Compose dialog

Replaced bookmark editing with separate Compose Screen/Route and the existing public Fragment constructors. Read-only chapter text, original text and notes preserve exact whitespace; delete remains limited to existing bookmarks. Transparent outside cancellation, IME actions, focus/selection restoration and busy protection retain host behavior. Resumed close delivery runs once; small argument IDs and durable drafts restore across Fragment recreation. Added compilation of 7 real Compose behavior tests and 3 actual Fragment/Room/recreation tests. Removed the exclusive bookmark layout. Full JVM tests and Android test compilation validate integration; Android device execution is unavailable.

### Replacement rule group management data and state

Added a reusable immutable named-group state model and a replacement-rule repository. Transactional add assigns only ungrouped records; rename/delete change exact memberships while retaining other groups and complete rule metadata. Saved editor targets follow names across reordered lists, failed mutations retain retryable input, and active mutations block repeated actions. Canceled noncooperative results cannot publish state. Added 5 JVM behavior tests and compilation of 3 real Room metadata/ungrouped/SQL-wildcard cases. Existing group dialog remains for separate Compose integration; Android device execution is unavailable.

### Theme list data and saved effects

Added immutable theme snapshots, content-based row identities and private share receipts that keep large theme JSON outside Bundle state. List/import/delete/save share a synchronized storage gate; exact snapshot deletion avoids applying stale row indexes. Theme application separates file/image/preferences preparation on I/O from the existing main-thread theme transition and recreation event, retaining the download-then-reapply policy. AtomicFile saving and backup recovery protect the config list. Canceled noncooperative reads/share writes cannot publish stale UI effects. Added 5 repository and 8 ViewModel JVM tests plus compilation of 3 actual file/concurrency/thread-boundary tests. Legacy list UI remains for separate Compose integration; Android device execution is unavailable.

### RSS favorites list data and saved state

Added immutable favorite rows excluding large article bodies and a single Room stream retaining group ordering and reverse favorite time. Length-bounded composite hashes distinguish identical links from different origins. Reading re-reads the current complete entity, then consumes its small pending key only at resumed host delivery. Group selection, independent scroll positions and row/group/all-delete confirmations restore; missing groups fall back safely. Active deletion blocks repeats, and stopped or canceled noncooperative operations cannot publish late state. Added 10 ViewModel JVM tests plus compilation of 3 real Room ordering/key/metadata/deletion tests. Legacy list UI remains for separate Compose integration; Android device execution is unavailable.

### Theme list Compose dialog

Replaced the theme list with stable-key Compose rows, separate apply/share/delete controls, captured deletion confirmation and clipboard import. Public refresh/index bridge methods remain; theme JSON sharing prepares a durable receipt before consumed resumed-host delivery. Canceled noncooperative share reads recheck coroutine activity, so a pause/resume cannot dispatch an old read. Added compilation of 6 Compose interaction/lifecycle/restore tests and 2 real Fragment/clipboard/recreation tests. Removed the exclusive theme row layout and menu; shared recycler consumers remain. Full JVM tests and Android test compilation validate integration; Android device execution is unavailable.

### Replacement rule group management Compose dialog

Replaced the replacement-rule group manager with a reusable named-group Compose screen and its own saved editor state. Stable name keys preserve edit/delete targets across list changes, editor cancellation leaves memberships unchanged, and busy mutations prevent dismissal or repeat actions. Existing 90-percent dialog sizing and public callers remain. Added compilation of 3 real Compose interaction/narrow-dark-layout tests and 1 actual Fragment/Room/recreation test preserving all rule metadata. Shared group layouts and menus retain other consumers. Full JVM tests and Android test compilation validate integration; Android device execution is unavailable.

### Automatic task editor data and saved drafts

Added immutable task fields, full JSON copy/paste projection and revisioned private drafts with small saved session/effect identifiers. Save validates name, cron and normalized script, retains current Room runtime metadata and original IDs, rejects deleted existing tasks and allocates new task order. Completion receipts restore save-before-close/debug/login delivery; no-login still saves. Code editing transfers large text through files, persists returned text/cursor before cleanup and rejects canceled late UI delivery. Dirty exit confirmation ignores cursor-only movement. Added 15 JVM behavior tests plus compilation of 6 actual Room/draft/transfer tests. Legacy editor host remains for separate Compose integration; Android device execution is unavailable.

### Automatic task editor Compose page

Replaced the task editor with stable-key Compose fields, both flags, code-field selection/navigation, IME actions, copy/paste/help and dirty-exit confirmation. Public task intents and CodeEdit file/cursor results remain; save completes before close/debug/login and RESULT_OK is preserved. Resumed effects consume before native host calls, with cancellation checks after clipboard preparation and draft flush on lifecycle stop. Added compilation of 5 real Compose behavior/lifecycle tests and 3 actual Activity/Room/native-intent/CodeEdit/back tests. Replaced only obsolete editor source/XML assertions while retaining normalization, cron and other-page contracts. Removed the exclusive editor layout and menu. Full JVM tests and Android test compilation validate integration; Android device execution is unavailable.

### RSS favorites Compose page

Replaced favorites with stable-key Compose group tabs, swipe pages, independently restored scroll positions, image previews and row/group/all-delete confirmation. Resumed read delivery resolves current complete article metadata and preserves pending navigation through canceled reads. The XML-free Activity container retains the existing Fragment ReadRss contract for text/image/video navigation and cleans old pager fragments without removing dialogs. Image leases preserve source authentication, crop/animation and lifecycle release. Added compilation of 8 actual Compose interaction/lifecycle/image tests and 4 real Activity/Room/recreation/migration tests. Removed the old adapter/empty ViewModel and exclusive Activity layout/menu; shared RSS article layouts retain consumers. Full JVM tests and Android test compilation validate integration; Android device execution is unavailable.

### Book source group management Compose dialog

Replaced book-source group management with the shared stable-key Compose group screen and saved editor state. Its I/O repository performs membership edits in Room transactions, assigns added groups only to ungrouped records and retains complete source metadata and existing DAO check-state behavior. Public dialog callers and sizing remain. Existing shared state/UI tests cover repeat guards and canceled results; added compilation of 2 actual Room metadata/membership cases and 1 real Fragment/editor-recreation test. Shared layouts/menu still have RSS-source and highlight consumers. Full JVM tests and Android test compilation validate integration; Android device execution is unavailable.

### Highlight group management data and state

Added an I/O highlight-group repository retaining whole-label trim matching and NOCASE ordering, distinct from comma-separated source memberships. Saved rename/delete/move stages distinguish actual no-group labels from moving rules to null. Blank rename remains a no-op, failures retain retryable dialogs, and successful delete/move produce consumed refresh receipts. Lifecycle observation can pause/retry without canceling owned mutations; canceled noncooperative results cannot publish stale state. Added 7 JVM state tests and compilation of 3 actual Room full-metadata/move/delete/order tests. Legacy group host remains for separate Compose integration; Android device execution is unavailable.

### Highlight group management Compose dialog

Replaced highlight group management with Compose stable-name rows and independent rename/delete/move dialogs. Whole-label matching, trimmed blank rename, actual no-group labels versus null destinations and reader refresh behavior remain. Lifecycle observation pauses with the view; resumed refresh consumes before updating the reader. Active mutations disable closure and repeated actions. Added compilation of 5 real Compose failure/lifecycle/restore tests and migrated existing manager integration flows while preserving their full metadata/filter/rename/move/delete assertions and editor coverage. Shared group layouts still retain source consumers. Full JVM tests and Android test compilation validate integration; Android device execution is unavailable.

### RSS source group management Compose dialog

Replaced RSS-source group management with the shared stable-name Compose screen and saved editor state, retaining its explicit Done control. Transactional I/O membership edits preserve complete RSS-source rules/metadata and only assign added groups to ungrouped sources. Existing shared state/UI behavior coverage remains; added compilation of 2 actual Room membership/metadata cases and 1 real Fragment/editor-recreation test. With the last source and highlight consumers migrated, removed the shared group-row layout and group-manager menu, including obsolete native IDs from the existing highlight integration helper. Other shared dialog layouts retain their current consumers. Full JVM tests and Android test compilation validate integration; Android device execution is unavailable.

### Automatic task debug data and recovery

Moved task debugging behind an I/O repository with immutable execution snapshots, scoped idempotent Debug leases and the unchanged Runner persist=false behavior. Private revisioned records retain bounded logs and run markers; process recovery does not automatically repeat completed/interrupted execution. A separate ViewModel retains first automatic run, explicit rerun and close, while pause continues execution. Rerun cancels and joins the previous job before acquiring a new global callback owner. Cancellation/generation guards reject late output and load failures. Added 11 JVM state tests and compilation of 5 actual Room/Rhino/lease/cancellation/disk tests, retaining existing output helper tests. Existing Compose host remains for separate integration cleanup; Android device execution is unavailable.

### Automatic task debug Compose lifecycle integration

Updated the existing Compose debug page to use an explicit saved-state repository factory, resumed single close delivery and immediate scoped release on back/finish. Pausing flushes bounded output while execution continues; configuration recreation retains the same owner. Surface supplies dark-theme content colors; selectable monospace logs retain automatic latest-output scrolling, explicit rerun and interrupted recovery status. Added compilation of 5 real Compose selection/copy/lifecycle/rerun/small-screen tests and 2 actual Activity/Runner/configuration/cancellation tests. Shared JS, Debug, Runner and scheduler implementations remain intact. Full JVM tests and Android test compilation validate integration; Android device execution is unavailable.

### Local file picker data and saved state

Added immutable file/directory projections and an I/O filesystem repository, retaining directory-first name ordering, existing hidden-entry visibility and exact extension disabling. Canonical fixed-root checks prevent traversal and external symlink creation; final confirmation revalidates the selected file/directory. Small directory/selection/scroll/result state and saved folder-editor selections restore independently. Active create/confirm guards repeated operations and canceled noncooperative results cannot publish late state. Typed validation issues allow localized UI messages while platform errors retain their details. Added 6 actual temporary-filesystem JVM tests and 11 state tests. Legacy picker host remains for separate Compose integration; Android device execution is unavailable.

### Local file picker Compose page

Migrated FilePickerDialog to a lifecycle-aware Compose Route and stateless Screen, retaining exact extension filtering, current-directory selection, folder creation, navigation, both callback targets, and host dismissal. Typed picker issues use English and Chinese resources. Removed the former ViewModel, two exclusive layouts and menu; shared path-picker assets remain. Added ten Compose tests and three host restoration tests. Validated with the complete JVM suite and Android test compilation; device tests require a connected Android device.

### Shared local book preview data and state

Added an IO repository for immutable file metadata and a SavedStateHandle ViewModel for shared local-book preview selection, scrolling, busy state and one-time action tickets. Full Book objects, copy/parsing and final import remain owned by the existing FileAssociation pipeline. Added seven JVM tests and two actual FileDoc Android tests. Validated with all JVM tests and Android test compilation.

### Shared local book preview Compose dialog

Replaced ImportLocalBookDialog with a stateless Compose Screen and lifecycle-aware Route while preserving FileAssociation selection, directory chooser and import calls. Revalidates selected IDs against the current batch before consuming action tickets; rotation retains selection and scroll without changing imported files. Added seven Compose and two real host tests; updated only shared import preview interactions. Shared recycler/import-book resources remain in use elsewhere. Validated with the full JVM suite and Android test compilation.

### Bottom browser Compose shell

Replaced BottomWebViewDialog XML/ViewBinding with a Compose Route and stateless Screen. AndroidView contains only the original native WebView and fullscreen-video surfaces; existing browser configuration, requests, JavaScript, download, back navigation, sheet sizing and pool ownership remain in the kernel. The fullscreen slot mounts only while active and composition is disposed before the pool lease is released. Removed one exclusive layout and replaced eight source-string assertions with five actual interop/host tests. Existing browser show, sizing, request and shared image-decoder tests remain. Validated with the full JVM suite and Android test compilation.

### RSS source editor durable draft and save repository

Added immutable drafts for all 33 text fields and eight flags, preserving source metadata and rule completion. Room saves update renamed article/favorite origins transactionally and retain cache invalidation behavior. A durable pre-transaction journal and full JSON fingerprints recover committed saves after invalidation or draft-write failures while rejecting concurrent target changes. Disk drafts and code-editor transfer files keep large rules out of saved-state payloads. Added four JVM projection tests and ten actual Room/disk recovery tests. Validated with the complete JVM suite and Android test compilation.

### RSS source editor saved state and actions

Added a ViewModel for the four tabs, focus, all field text/selection, editor options, undo/redo, unsaved-exit decisions and lifecycle-delivered actions. Saved state retains small session and transfer identities; large drafts use private files. Save actions await durable repository receipts; cancelled saves and native editor returns recover without publishing UI actions after stop. Added twelve JVM tests for restored drafts, metadata, cancelled save/result handling, retry and exact-once delivery. Validated with all JVM tests and Android test compilation.

### Chapter source search repository

Moved pure result-order/filter policy to the model layer with the former package API retained as a compatibility delegate. Added IO search/store boundaries for cached results, source groups, bounded concurrent searches, word-count measurement, scores and current-source pinning. Results persist and publish sequentially per source; a later source failure retains earlier successful rows and never cancels other sources. Added ten JVM tests and two actual Room store tests. Validated with all JVM tests and Android test compilation.

### Chapter source content and recoverable cache commits

Added private immutable chapter sessions and disk receipts for fetched content, merged chapter caches and source-change actions. Cache journals precede body writes; an atomic BookHelp fence verifies the previous body hash before recovery and prevents stale receipts overwriting a newer body or chapter metadata. An explicit retry action may abandon only uncommitted cache intents. Added nine JVM tests and four actual Room/cache tests, including concurrent writer rejection and already-written receipt recovery. Existing BookHelp save behavior remains unchanged. Validated with all JVM tests and Android test compilation.

### Chapter source state and automation

Added immutable search, TOC, selection and automation state with private disk sessions, preference boundaries and small saved identities. Durable cache receipts advance batch progress once, stop requests finish an already-started commit, and ambiguous/missing matches pause for explicit chapter selection or skip. Restoring never restarts pending network work automatically. Current-source deletion waits for a successful replacement action. Added thirteen JVM tests covering recovery, IO failures, cancellation and query changes during projection. Validated with all JVM tests and Android test compilation.

### RSS source editor Compose page

Replaced RSS source edit Activity, native field adapter and exclusive XML with a stateless Compose Screen and lifecycle-aware Route. All four tabs, 33 fields, options, independent scrolling, custom keyboard assists, syntax display, native full-screen editor, unsaved-exit confirmation and save-before-debug/login/variable actions remain. Added IO repositories for assist preferences and QR sharing. Added sixteen Android Compose/host/repository tests; migrated only obsolete RSS editor source assertions in shared suites. Book editor resources and behavior tests remain. Validated with all JVM tests and Android test compilation.

### Replacement rule editor data and state

Added immutable eight-field drafts, per-field undo/redo, preview state, small saved identities and private file editor transfers. Durable pre-save journals recover Room/sample/draft failures using fixed receipts and reject concurrent rule changes; native editor results persist before transfer cleanup and support explicit retry/discard. Preserved metadata, sample normalization, paste identity and original preview algorithms, moving the engine to model/replace with compatible UI delegates. Added fourteen JVM and seven actual Room/disk tests; original engine tests remain. Validated with all JVM tests and Android test compilation.

### RSS source debug sessions

Added an immutable Room source/sort repository and owned Debug execution lease, retaining shared engine behavior and callback identity. Reruns join previous parser children before acquiring a successor; stop releases only the current owner. ViewModel state restores queries, selection and bounded logs from private disk records without automatically rerunning interrupted work; list/content HTML stay out of saved state. Added ten JVM session tests and five actual Room/Rhino/local-HTTP/disk tests. Validated with all JVM tests and Android test compilation.

### Unused legacy layout cleanup

Removed fourteen orphan layouts after checking committed production, test and module sources for layout names, generated Binding names and XML references. These include obsolete donate/group shells, loading templates, login rows and unused single-selection/video/tab templates. Active chapter-source and replacement-editor migration resources are excluded. Validated through complete production/Android-test compilation and JVM tests to detect missing generated resources.

### Replacement rule editor Compose page

Replaced ReplaceEditActivity XML/ViewBinding and the legacy ViewModel with a Compose Route and stateless Screen. Preserved all eight fields, four flags, selectable preview, original paste/copy/save APIs, unsafe-text editor entry, cursor-only returns and unsaved-exit decisions. Custom keyboard assist keys and row preferences update live; failed native results expose retry/discard. Removed the exclusive layout/menu and replaced the old cursor wiring assertion with actual host coverage. Added eight Compose, three host and two Room/preference tests. Validated with all JVM tests and Android test compilation.

### Chapter source Compose dialog

Replaced ChangeChapterSourceDialog and its source/TOC adapters with Compose Route and stateless Screen while preserving its constructor and callback API. Retained live source groups, scores, five source actions, current-source pinning, directory navigation, chapter selection, batch ranges and lifecycle-gated automation/receipts. Close requests respect cache commit and stop-after-current rules. Ordinary content failures clear the directory while batch failures retain retryable selection. Removed the exclusive layout and old ViewModel; pure compatibility policies moved to model/book. Added eight Compose and one real Room group-flow test, plus the fourteenth ViewModel regression. Shared suites retain Book-source and real helper/fence assertions; obsolete Chapter source mirrors are replaced by independent behavior coverage. Validated with all JVM tests and Android test compilation.

音频片头片尾设置的数据层独立迁移：不可变草稿保留原始秒数，仅控件展示限制在 0–180；全局和本书切换、串行写入与真实关闭保存分别处理。保存前读取最新 Room 书籍，只合并三个音频配置字段，保留阅读进度、自定义封面与其他配置。新增 6 个 JVM 测试和 2 个实际 Room Android 测试；完整 JVM 测试与 Android 测试编译验证，设备测试待运行。

RSS 源调试页面迁移至 Compose Activity/Route/Screen，保留搜索帮助、分类选择、日志选区复制和自动网页链接、列表与正文 HTML 入口。调试执行由独立 ViewModel 与拥有者租约管理，暂停显示期间继续执行，返回关闭时释放租约，旋转不重复启动。删除旧 Model/Adapter、独占布局和菜单；新增 11 个 Compose、生命周期与真实宿主 Android 测试。完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

键盘辅助配置的数据层与 ViewModel 独立迁移：观察所有类型的辅助键，使用稳定主键处理增删改和完整列表排序；保留空键与 Room 替换语义，编辑后转为 type 0 并保留原序号。编辑草稿和数据库提交日志存入私有文件，小 SavedState 仅保存会话、选区位置和待发送行数；拖动取消不写入，行数确认提交后单次发送。新增 8 个 JVM 与 5 个真实 Room/文件 Android 测试；完整 JVM 测试与 Android 测试编译验证，设备测试待运行。

清理无消费者的 item_1line_text.xml：对生产、测试、模块源码与 XML 引用以及动态 getIdentifier 调用检查后确认没有布局或生成 Binding 消费者。现有 item_1line_text_and_del.xml 的键盘与补全消费者保留。删除后完整 JVM 测试和 Android 测试编译验证。

音频片头片尾页面迁移至 Compose Dialog/Route/Screen 和不可变状态 ViewModel，保留本书/全局、0–180 秒、加减和滑块松手提交。切换范围前串行落盘并读取最新全局值，过期读取不覆盖新的操作；旋转保留草稿且不保存书籍，真实关闭进入持久写入并合并最新书籍配置。删除独占 dialog_audio_skip_credits.xml，新增 6 个 JVM、3 个 Compose 和 1 个真实宿主/Room Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

书源校验配置的数据层与不可变状态 ViewModel 独立迁移：读取和保存移至 IO，保留入口检查项的互斥回退及详情、目录、正文联动，取消不保存。超时输入限制为小型数字草稿，确认时检查空值、非正值和毫秒乘法溢出；进程恢复保留未保存选择，重复保存被阻止，失败后可修改并重试。新增 5 个 JVM 行为测试，完整 JVM 测试及 Android 测试编译验证。

书源校验配置页面迁移至 Compose Dialog/Route/Screen，使用可访问的整行复选控件、自动换行和可滚动输入区；确认保存完成后仅在 RESUMED 交付关闭，保存期间禁止取消，普通取消不写设置。旧 dialog_check_source_config.xml 与 ViewBinding 删除，调用者无须改变。新增 2 个实际 Compose Route 交互测试，覆盖联动保存、溢出后纠正及取消不写；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

键盘辅助配置页面迁移至 Compose Dialog/Route/Screen，保留所有类型的辅助键、编辑转 type 0、即时删除、拖动后完整排序和 1–5 行设置。拖动手柄与长按均支持取消，增加可访问的上下移动操作；超大文本通过正式 CodeDialog 文件协议编辑，并校验会话避免迟到回调。普通保存失败仍可改字段；未落库的冲突日志可取消而不改外部行，已落库的日志按原提交完成。删除独占菜单，保留其他消费者使用的共享布局。新增 7 个 Compose、3 个宿主测试及 2 个 Room 提交恢复测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

整本换源搜索基础复用已迁移的章节搜索 Repository，增加默认启用的当前源置顶参数，整本换源保持原不置顶策略；当前书籍仍提供相对字数基准。编辑单一书源后的刷新只移除该源旧结果，保留其他源的完整元数据、评分及中途成功结果；先在完整结果上计算基准，再筛选名称，避免隐藏当前书后丢失相对基准。新增 6 个 JVM 和 1 个实际 Room Android 测试；章节原默认行为保留，完整 JVM 测试和 Android 测试编译验证。

底栏图集分配的数据层和不可变 ViewModel 独立迁移：暂存文件映射为文件名 ID，缩略图在 IO 解码，保存仍由现有图集管理器负责校验、命名、重命名和当前主题迁移。四个槽位可清空选中图并同步清空普通图，至少一个选中槽位即可保存；保存期间不能丢弃暂存会话，旋转与进程恢复保留选择和选区。小 SavedState 不含 Bitmap，巨量名称粘贴不进入保存状态，丢弃失败可重试。新增 10 个 JVM 与 4 个真实 PNG/ZIP/图集 Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

整本换源准备与交付数据层独立迁移：普通网页文件保持不获取目录的原路径，自动替换候选仍要求目录；书籍、书源、章节完整元数据和结果回执存于私有 AtomicFile 会话。跨实例串行写入拒绝旧版本，消费和成功确认标记不回退；只有原宿主换源成功回调确认后才执行既有旧源删除，准备失败、取消及未确认结果均保留旧源。新增 6 个 JVM 与 2 个真实 Room/文件 Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

缓存下载与导出管理的数据层和 ViewModel 独立迁移：Room 流保留非音频筛选及五种排序，缓存扫描与下载/导出运行状态在 IO 获取，迟到结果不会覆盖新分组；增量保存事件去重。设置按字段串行写入，失败字段同步成功前阻止导出；导出选择和大段 EPUB 范围、命名脚本存入私有 AtomicFile 票据，SavedState 仅保留小 ID 与标记。固定数量跨实例票据锁保护读写和释放，释放后迟到写入不再创建文件。新增 12 个 JVM 与 8 个 Room/AtomicFile/Rhino Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

底栏图集分配页面迁移至 Compose Activity/Route/Screen，保留四个按钮的选中/普通图片、自动预填、2–4 列可取消调色板、名称编辑与至少一个选中图的保存规则。缩略图由 Repository 在 IO 解码；保存阶段禁用返回，旋转保留选择及暂存会话，明确退出后丢弃会话，完成回执仅在 RESUMED 消费后关闭。删除旧 ViewModel、四个独占布局和独占菜单，调用 Intent 和图集管理器保持兼容。新增 5 个 Compose 和 2 个真实宿主 Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

底栏图集管理的数据层与 ViewModel 独立迁移：列表、缩略图、启用、ZIP 暂存、编辑、导出分享均在 IO 委托原图集管理器；导出交付文件路径而不在 SavedState 保存 ZIP 字节。小型效果回执保留原文件选择与图集分配入口，未成功导航的暂存会话可清理，取消后迟到结果不再导航；恢复加载不覆盖待交付结果。预览版本在重新加载后递增，确保同名图集编辑后重新解码。新增 8 个 JVM 与 4 个真实图集/PNG/ZIP Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

整本换源 ViewModel 独立迁移：小 SavedState 只保存会话、搜索展开和已消费回执 ID；完整请求、结果与类型确认状态存入私有会话。过期网络和非合作取消后的结果不更新状态；当前书行点击不触发换源，类型不同需确认，当前源删除先选择不同来源的同类型候选。普通准备可取消，自动替换准备不可取消；关闭前消费一次结果，成功确认后才删除旧源。恢复时以磁盘版本递增草稿，设备重启后也不会被旧时间版本拒绝。新增 13 个 JVM 行为测试；完整 JVM 测试和 Android 测试编译验证。

全部书签的数据层与 ViewModel 独立迁移：保留 Room 排序，列表使用有界预览，导航时按书签时间重新读取完整原文、摘要、章节位置和对应书籍；长按始终进入编辑。恢复状态只保存小型回执、目录请求格式与滚动位置，取消后的迟到查询不触发导航，重复目录结果不重复导出。JSON 保留完整字段，Markdown 保留原文档格式及分组规则。新增 8 个 JVM 与 4 个 Room/真实文件 Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

缓存下载和导出页面迁移至 Compose Activity/Route/Screen，保留分组、下载确认、单书操作、六项布尔设置、类型/编码、命名脚本与自定义 EPUB 范围及预览。原下载、导出服务继续执行后台任务；页面退出清理未交付票据，已开始的服务交付完成后再释放。目录返回结果先写入票据，早于状态恢复的返回及重复旧回执不会丢失或重复交付。普通命名脚本和编码编辑草稿也存磁盘，SavedState 不包含长文本。删除两旧类、三个布局和独占菜单；新增 6 个 JVM 测试及 12 个 Android 行为测试，完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

底栏图集管理页面迁移至 Compose Activity/Route/Screen，保留默认图集、响应式网格、当前标记、长按编辑/导出/分享/删除及 ZIP 导入；使用独立文件选择器并向导出宿主交付 ZIP 文件路径。同名编辑后按预览版本刷新缩略图。启用和删除阶段保护返回，待主界面变更通知交付后解除，恢复中断操作也能刷新并退出；长耗时 ZIP 暂存仍可退出并清理会话。删除两个独占布局和菜单。新增 2 个 JVM 与 9 个 Compose/宿主 Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

全部书签页面迁移至 Compose Activity/Route/Screen，按书名与作者分组显示粘性标题，保留单击阅读/缺书编辑、长按编辑和 JSON/Markdown 导出。导航读取后再次检查 RESUMED 与 FragmentManager 状态，消费回执后才调用阅读器或正式书签编辑器，旋转不重复打开。滚动恢复等待数据到达；保留 TOC 使用的共享 item_bookmark 布局。删除本页旧 ViewModel、已无消费者的 Adapter/Decoration、独占布局与菜单。新增 6 个 Compose 和 2 个真实宿主 Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

直链上传配置的数据层与 ViewModel 独立迁移：不可变草稿保留上传地址、下载规则、注释、压缩和原有效期校验顺序；默认规则及测试上传在 IO 委托现有实现，仅明确保存才修改配置。私有 AtomicFile 保存完整脚本与测试结果，SavedState 只保存 UUID；修订号和跨实例锁拒绝旧写入，取消后不发布迟到测试结果，释放的草稿不会被迟到写入重建。新增 8 个 JVM 与 3 个真实 AtomicFile Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

整本换源页面迁移至 Compose Dialog/Route/Screen，保留来源分组、筛选、排序与测量选项、评分、五项行操作、类型确认、当前源定位和成功后删除旧源的回调 ABI。手动刷新在关闭自动字数测量时仍执行，并替代当前搜索；缓存筛选沿用原 SQL 匹配，后续搜索结果按书名筛选，参考字数先按完整结果计算。缺少相对字数基准的提示仅在 RESUMED 消费一次，成功确认任务在宿主关闭后仍可完成。删除两旧类、两个独占布局和菜单，保留真实结果/成功回调契约测试。新增 3 个 JVM 与 11 个 Compose/宿主/真实 WebFile 解析 Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

阅读记录的数据与封面层独立迁移：保留 DAO 的作者编码、跨设备聚合、三种排序和不随筛选改变的全局统计，导航使用精确书名/作者与最新书架记录。删除和移除历史作者仍调用原 DAO 并在提交后清理封面缓存，偏好设置仅写变更字段。封面沿用正式 Glide 资源所有权，依次读取当前书架、历史快照和独立日夜备用封面，失败保持白色且不生成标题。新增 6 个 Room/偏好及 2 个真实 Glide/PNG Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

直链上传规则配置页面迁移至 Compose Dialog/Route/Screen，保留五个配置字段、整行压缩开关、默认规则选择、复制/粘贴和明确发起的测试上传；测试结果支持选择与完整复制。表单根据窗口高度滚动并适配 IME，校验提示本地化；保存期间禁止取消，完成仅在 RESUMED 关闭，旋转保留草稿和已完成测试结果，取消释放本会话而不保存配置。删除独占布局和菜单，新增 1 个 JVM 及 5 个 Compose/宿主 Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

目录书签标签的数据层与 ViewModel 独立迁移：保留书名/作者范围、章节/摘要 SQL 筛选与章节顺序，每次数据库发射仍定位阅读章节之前的最后书签。单调滚动回执不被旧确认吞掉；导航按时间重新读取完整元数据，暂停后迟到查询不消费结果。完整查询与身份存私有 AtomicFile，SavedState 仅存 UUID、修订号、小导航回执和身份摘要；写前以磁盘版本为基准，固定 64 个锁保护跨实例读写，真正清除 owner 后释放全文草稿并拒绝迟到重建。新增 11 个 JVM 与 6 个 Room/AtomicFile Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

阅读记录 ViewModel 与草稿层独立迁移：查询、删除确认、作者标签和导航目标存私有 AtomicFile，SavedState 仅保存票据；过期查询和迟到导航不更新页面，删除必须明确确认，导航消费先持久化以避免重复打开。偏好按字段即时展示并串行保存，版本保护迟到的写后读取及恢复读取，失败字段不会被其他成功写入隐藏，可明确重试。页面暂停保留草稿，退出释放自身票据。新增 13 个 JVM 与 2 个 AtomicFile Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

目录页的书签标签迁移至 Compose Fragment/Route/Screen，保留原宿主搜索桥接、单行卡片、空原文/摘要隐藏、数据库更新后的阅读位置定位和快速滚动。导航读取后再次检查生命周期并消费一次回执，再返回章节/位置或打开正式书签编辑器；销毁视图仅释放自身宿主回调，旋转保留 owner 与草稿。删除最后一个书签 Adapter、item_bookmark 布局及失效布局镜像测试；共享 fragment_bookmark 布局仍供高亮标签使用。新增 6 个 Compose 与 2 个真实宿主 Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

书内搜索的算法与数据层独立迁移：保留 literal 非重叠匹配、正则及无效模式处理、原 UTF-16 阅读位置和两侧 20 字符片段；在线书仅搜索已缓存章节，本地书读取全部章节，按原章节顺序累计结果。每章读取当前净化/正则选项，支持搜索期间下载的新章节，取消后不交付迟到结果。完整书籍/章节元数据与大型查询结果存私有 AtomicFile，固定锁和关闭回执保护释放后不被旧 owner 重建，其他会话保持独立。新增 13 个 JVM 与 3 个真实 AtomicFile/Room/缓存 Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

二维码扫描的数据层独立迁移：图库文件以流读取并在 IO 解码，解析结束释放 Bitmap，保留可读但无二维码时返回空结果的原合同。完整扫描文本与待解析图库地址存私有 AtomicFile，修订号与固定跨实例锁拒绝旧结果覆盖，释放后迟到写入不能重建本会话。新增 3 个真实 AtomicFile/PNG/二维码 Android 测试；完整 JVM 测试和 Android 测试编译验证，设备测试待运行。

阅读记录页面改为独立 Activity、Route 与无状态 Compose Screen：保留两种布局、全局统计、三种排序、五项设置、同名不同作者的精确操作、封面及阅读器分流。持久化导航在生命周期暂停时回滚票据，恢复后只交付一次；日期为零时继续显示空值。删除五个专属布局、旧菜单与 XML 镜像测试，新增 Compose 布局和生命周期测试，原 6375 条记录及 Room/封面/阅读器回归改用实际 Compose 操作。隔离 JVM 回归和 Android 测试源码编译通过；设备测试尚未执行。

书内搜索状态层迁入专属 ViewModel：完整查询、结果和原阅读器定位字段保存在私有会话，SavedState 只留小票据。初次搜索只提交一次，停止保留部分结果，旧任务不得覆盖新查询；选中结果先持久化、交付后标记消费，磁盘失败可重试。新增 10 个 JVM 状态/恢复/取消/持久化回归；隔离全量 JVM 与 Android 测试编译通过。

二维码状态层使用私有会话与小型 SavedState 票据，图片选择回执先落盘、解码完成后再发布结果。保留空解码结果返回 RESULT_OK 的旧约定，重复扫描、取消后的迟到结果和恢复重放均被拦截；暂停或取消结果认领时回滚，真实关闭清理所属会话。新增 8 个 JVM 恢复、持久化失败重试、生命周期票据和取消回归；隔离全量 JVM 与 Android 测试编译通过。

目录标注数据与状态层独立迁移：书籍 URL 隔离查询，按最新章节 URL 映射、正文位置和时间排序，展示有界投影，阅读及备注前重新获取完整实体。完整查询保存在 UUID 私有会话，SavedState 保留小票据；持久化修订基于磁盘基线，清理后迟到写入不会重建。保留原坐标、标题长度、锚点与颜色合同，新增 15 个 JVM 与 7 个 Room/私有会话回归；隔离全量 JVM 与 Android 测试编译通过。

目录标注 Fragment 改为 Compose Route/Screen，保留卡片、颜色条、章节/原文/备注、点击阅读及长按备注，复用 Compose 快速滚动。异步解析后再次核对 RESUMED 和宿主状态，消费票据后交付，孤立章节继续可编辑但不跳转。删除旧 Adapter 及最后两个专属布局；新增 7 个 Compose 和 3 个宿主回归，只替换共享测试中该页旧 XML/源码断言。隔离 JVM 回归与 Android 测试编译通过；设备测试尚未执行。

二维码页面迁入 Compose Activity/Route/Screen，保留图片选择与 RESULT_OK/result 可空字符串协议。CameraX PreviewView 作为原生扫码内核，QR-only/full-area/0.8 区域与缩放、灯光感应、手电筒保留；工具栏、扫描框、进度及错误重试由 Compose 绘制，原 Fragment/布局/菜单删除。结果认领失败显示可重试错误，暂停认领后恢复仅交付一次；真实结束清理会话，旋转保留。新增 4 个实际 Compose/生命周期回归；隔离全量 JVM 与 Android 测试编译通过，尚未执行设备相机测试。

书内搜索页面改为 Compose Activity/Route/Screen，保留输入、替换/正则开关、逐章进度、停止、上下滚动、电子墨水高亮与全部阅读器 ABI 字段。完成标志绑定已落盘结果修订，恢复不把部分结果误报完成；真实关闭清理 AtomicFile 的 base/bak/new，旋转保留完整会话。旧 VM/Adapter、两个专属布局和菜单删除，新增 9 个 Compose、2 个宿主与 1 个阅读器 ABI 回归，状态层另补两个恢复/跨页设置用例。隔离全量 JVM 与 Android 测试编译通过；设备测试尚未执行。

规则订阅管理的数据层拆为不可变投影与 Room Repository：新增按 maxOrder 追加，编辑在事务中合并最新调度/脚本字段，空 URL、重复 URL 与并发删除均拒绝覆盖。拖拽提交保留唯一排序值，旧重复值只在显式提交时归一，并纳入并发新增记录；原订阅更新调度器继续使用同一 Room 表。新增 6 个实际 Room 事务回归；隔离全量 JVM 与 Android 测试编译通过。

章节目录基础迁移：普通分卷、EPUB 层级与 PDF outline 算法移至 model/book/toc，原调用仅更新 import；新增专属 Repository/ViewModel，有界不可变行投影、150ms 搜索、定位/折叠/排序、最新 URL 导航与 PDF 原始页码合同。完整书籍/查询/折叠状态存私有会话，SavedState 只保留小票据。审查补齐 host ReadConfig 深复制及 text/audio 缓存枚举期间事件合并和树隔离，新增 13 个 JVM 与 6 个实际 Room/文件回归；隔离全量 JVM 与 Android 测试编译通过。

通用网页外壳的基础数据层独立迁移：完整请求、来源 JSON、HTML、图片与验证结果使用不可变 DTO 和私有会话；URL 分析、POST、脚本注入、Cookie、图片保存及来源操作均由 Repository 在 IO 执行。保留原验证重新请求/捕获内容协议、历史重复项/about:blank/data 返回逻辑，AtomicFile 修订与关闭围栏防止迟到覆盖/复活并清理所有临时文件。新增 8 个 JVM 与 2 个实际文件回归；隔离全量 JVM 与 Android 测试编译通过。
