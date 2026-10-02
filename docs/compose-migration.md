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
