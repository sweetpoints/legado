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
