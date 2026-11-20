# Razr Fold: experimental three-column batch enter

## Runtime findings

The device reported an `OPENED` posture (id `4`) and a landscape inner display
of 2484×2232 px. WM Shell `dumpsys` showed empty split roots A/B/C (task IDs
4/5/6 in that boot, not stable identifiers). `SplitScreen.stageTypeToString`
maps the logical stage IDs 2/3/4 to A/B/C. `dumpsys activity containers` showed
Motorola Home under a separate fullscreen HOME root, not a split-stage root.

`work/NativeSplitProbe.java` loads the installed SystemUI path in a separate
`app_process` for Java reflection. Its optional `bounds` mode invokes the ROM
rectangle calculator on a detached horizontal model: 2484×2232, 26 px divider,
no insets. The numbers below are model output, not measurements of a live split
session. There is no APK/JAR extraction or decompilation.

| Code | Expected stages | Horizontal display rectangles, x only (left / center / right) |
| --- | ---: | --- |
| 5 | 0 | `0..832 / 832..1626 / 1652..2484`; not a supported three-stage state |
| 6 | 3 | `0..820 / 846..1666 / 1692..2512`; right edge extends 28 px beyond the screen |
| 7 | 3 | `-28..792 / 818..1638 / 1664..2484`; left edge extends 28 px beyond the screen |
| 21 | 3 | `0..1957 / 137..2347 / 526..2484`; overlapping rectangles, center-dominant snap |

The textual snap labels and internal divider labels do not reliably describe
the computed screen coverage. Replacing the two values on the detached
`SnapTarget` objects for 6/7 did **not** change the displayed rectangles.
Changing only a returned preview rectangle would not establish matching WCT,
SurfaceControl, divider, and touch bounds; no equalization hook is enabled.

The ROM exposes `StageCoordinator.activateSplit(int,int,WCT,boolean)`,
`updateActiveStagesForSnapPosition(int,WCT)`, `updateWindowBounds(manager,WCT)`,
`StageTaskListener.addTask(RunningTaskInfo,WCT)`, and `SplitScreenTransitions`
enter APIs. The detached `binding` probe invoked `addTask` with synthetic
tokens: the resulting WCT clears the child's requested bounds and reparents it
to the top of the stage root. The WCT was not applied. This is not proof that
reparenting Home works, nor of the arguments or call order for activating a
three-stage layout and playing its enter transition.

The detached `stage-order` probe also calls the ROM's
`StageOrderOperator.updateActiveStagesForGoingToLayout(int,WCT)` with three
synthetic stage roots. Snap `1` selects `[A,B]`; `6`, `7`, and `21` each select
`[A,B,C]`. Starting from `1`, each of those three-stage snaps keeps A/B in order
and adds C. At that step the WCT only unhides C; there is no task reparent or
layout change in this transaction. This identifies a stage-order preparation
step, not a complete enter transition. No synthetic transaction was applied.

The detached `prepare-anchor` probe generated a WCT to move one synthetic
task into A. `StageTaskListener.addTask` reset the child's requested bounds
and added the A reparent. Independently, `StageCoordinator.addActivityOptions`
put each listener's root token in `android.activity.launchRootTaskToken`.
This is evidence for targeting ordinary app launches, not evidence that a
HOME task can live in that root. See the expanded single-anchor probe below
for the limits of native setup, animation dispatch, and dismissal.

The ROM also exposes `SplitScreenController.SplitScreenImpl`
`requestEnterThreeAppsSplitSelectByGesture(boolean)`. Reflection in the
Launcher found `requestEnterThreeAppsSplitSelectByGesture(RunningTaskInfo[],
boolean,int)` on its select listener and `setSecondTask` / `setThirdTask` on its
selection controller. Launcher has a `SystemUiProxy.startThreeTasks` wrapper;
the corresponding named method was absent on the inspected SystemUI classes.
This is not evidence that the native gesture always accepts exactly one initial
fullscreen app, that it commits the right app immediately, or that it can
finish through the Launcher wrapper on this build.

## Implementation and limits

- The SystemUI three-dot menu opens a lightweight two-step picker over the
  current app. It lists other already-running split-capable fullscreen tasks
  in the same user and display, not all installed apps. Separate windows of
  the same app (including the anchor's app) are listed individually and
  identified by task ID. It does not create extra app instances or promote an
  already active two-way split. The first choice is
  stored for C (right); the second is for B (center). Neither opening the menu
  action nor making the first choice touches native split state or sends a WCT.
- The Shell handler requires the open, unlocked, landscape inner display, a
  focused eligible app for A, and initialized, empty A/B/C roots with no split
  or native split transition in progress. It revalidates task IDs and organizer
  tokens before committing, including the earlier choice. Back, Home/Overview,
  fold, portrait, screen off, and selection timeout discard the local choices
  without moving tasks. The picker does not reparent HOME or intercept launches.
- The commit runs against the live `StageCoordinator` and `SplitLayoutManager`.
  It prepares three active stages and native bounds, adds the three explicit
  `StageTaskListener.addTask` reparents in A/B/C order, raises the split root,
  and hands **one** WCT to `SplitScreenTransitions.startEnterTransition`.
  Snap `6` and extra transition type `1004` without a remote are experimental
  arguments, based on reflection and the detached three-child test. There is
  a verified live three-app enter trace for `batch-v3` below, but its native
  carousel bounds do not fit the display.
  The batch requests the ROM's native horizontal three-app direction before
  activating stages when the idle Shell layout is vertical. It checks that
  the layout remains horizontal after preparation and records the failing step
  and exception in the dump if the batch stops.
- The state is `IDLE → SELECTING_RIGHT → SELECTING_CENTER → COMMITTING → ACTIVE`.
  `ACTIVE` requires idle native transitions, visible split/dividers, matching
  child tokens, and the requested physical left/center/right order. Roll and
  exit use native `rollingStages` and `exitSplitScreen` only for this managed
  group. On a failed commit the module attempts native exit only after Shell
  settles and only if the anchor and the remaining children can be matched to
  this batch. Recovery and three-stage roll/exit still need live verification.
- The batch preserves native DPI and does not override freeform or ordinary
  two-app split. `batch-v3` in the SystemUI dump reports the actual stage and
  displayed rectangles, display bounds, divider gap, DPI, and equal-width
  reference. It does not override geometry. The confirmed live overhang
  requires a fix shared by task bounds, surfaces and divider/input geometry;
  see the `batch-v4` implementation below.

## Manual verification after loading through LSPosed

On the unfolded device in landscape, enable the experimental switch. Open
three separate split-capable windows in fullscreen (e.g. two existing
Chrome windows and one other app); leave app 1 in
front and use `⊞ 3画面整列`. Record the picker behavior before choosing
anything, after selecting app 2 for the right, and after selecting app 3 for
the center. The first two checkpoints should show no new split transition.
For each step check `adb shell dumpsys activity service
com.android.systemui/.SystemUIService WMShell` (including `NativeThreeColumn`),
and `adb shell dumpsys activity containers` for actual placement and Home.
Repeat with Home, Back, portrait rotation and fold during each pending step;
verify roll/exit and that ordinary two-app split still works. Do not infer
completion or equal-width coverage from the state label alone.

For a failed batch, collect the SystemUI dump and `logcat` immediately without
first navigating Home. Inspect `NativeThreeColumn`, `ShellSplitScreen`, and
`WindowManager` for the submit, enter finish or rejection, and any recovery.
The optional `NativeThreeColumnLauncher` dump remains diagnostic for the old
native gesture only; the new batch picker does not require that callback.

The diagnostic build also prints a bounded `NativeThreeColumn trace` in the
SystemUI dump. With the experimental preference enabled, snapshots of the
native `activateSplit`, `setupActiveStagesReady`, `prepareEnterSplitScreen`,
`startEnterTransition`, and `prepareExitSplitScreen` calls record arguments
and WCT operation counts only. Use a standard two-app split enter and exit
to obtain a live reference sequence; no gesture or three-app picker is required.

### Observed failure: fullscreen Chrome

On the device, a tap with fullscreen Chrome (task 9459) reached Launcher 1 ms
after the module's `START`. The existing Shell picker supplied `tasks=[]`,
`flag=true`, and `code=-1`; the Launcher callback returned `false`. Chrome
remained focused and all three stage roots stayed empty. No Home/Overview
cancellation was recorded for this attempt. This establishes that the native
gesture entry, as called by the bridge in this fullscreen state, does not start
the requested picker. A user-facing native gesture is not assumed to exist on
this device. Do not substitute guessed task lists or layout codes for a valid
picker session. The remaining work is to identify a start/selection path that
does not depend on a user-facing OEM gesture, and verify it with a controlled
WM Shell transition and rollback before changing live task placement.

### Live reference: standard two-app split enter and dismiss

Captured after loading the diagnostic APK (inner display 2484×2232). SystemUI
prints the same trace twice at different dump levels; the rows below are one
sequence. The hooks run **after** each method, so these are return observations,
not evidence that the methods are all sibling calls.

| Uptime (ms) | Shell method and observed arguments | WCT hierarchy ops / changes |
| ---: | --- | ---: |
| 380386309 | `activateSplit(1, -1, wct, false)` | 0 / 2 |
| 380386309 | `setupActiveStagesReady(1, wct)` | 0 / 2 |
| 380386330 | `startEnterTransition(wct, remote, handler, 1004, false, 1)` (`remote` present) | 5 / 4 |
| 380401627 | `prepareExitSplitScreen(2, 15, wct)` | 4 / 4 |

- Enter (`#17` in WindowManager logcat): `startIntentAndTask` selected snap 1,
  with `addActivityOptions` targeting B for the new app's pending intent and A
  for the existing task launch. The WCT also reordered the split root to the
  top, made it non-translucent, disabled reparent-on-relaunch, and set the
  animation delegate. `startPendingEnterAnimation` → `finishEnterSplitScreen`
  → divider shown and split made visible → `playAnimation` →
  `onTransitionAnimationComplete`. Live child bounds were A `0..1229` and B
  `1255..2484`, with a 26 px divider. No `prepareEnterSplitScreen` trace was
  recorded for this route.
- Launcher was brought forward as a **separate fullscreen HOME root** in
  transition `#16`, with a transient launch and a Quickstep recents animation.
  It went to the back in `#17`; the enter WCT does not launch or reparent HOME
  into A/B/C. This is not a capture of Launcher constrained to an empty column,
  and does not establish exactly when the selection UI is entered.
- Dismiss (`#19`): `moveTaskOutOfSplitScreen` → `prepareExitSplitScreen` with
  retained stage A (ID 2) and `MOTO_CLOSE_REQUEST` (15) → a dismiss session
  with A and `MOTO_FULLSCREEN_REQUEST` (16) → `startPendingDismissAnimation`
  → `prepareDismissAnimation` → `playFullScreenDismissAnimation` → dismiss
  finish and `onTransitionAnimationComplete`. The WCT reparents B's children
  to the default parent behind, A's children to the default parent on top,
  and lowers the split root. App 1 returned to `0,0..2484,2232`; the stage
  roots had no app children afterward. The preparation reason and the
  animation-session reason are distinct observations, not interchangeable.
- This run verifies dismissing a **committed** pair, not cancellation before
  a second app is selected. Next capture: start the stock picker from Overview,
  stop before selecting a second app, and dump WM Shell, activity containers,
  and Quickstep while the picker is visible. Then press Back without choosing
  an app and capture the same dumps plus logcat. Do not use the module's
  three-column action for this reference capture.

### Live reference: Overview picker before choosing the second app

During the instructed pause before the second selection, WM Shell reported
`isSplitActive=false`, `mDividerVisible=false`, no pending transition, and zero
children in A/B/C. App 1 (Chrome task 9468) was still a standalone fullscreen
task with bounds `0,0..2484,2232`. The focused Motorola HOME root had the same
fullscreen bounds. Quickstep reported a fullscreen device profile
(`isMultiWindowMode=false`) and no task assigned to either split stage.

WindowManager transition `#20` was a transient `TO_FRONT` recents transition:
the fullscreen HOME root moved to the front and app 1 moved to the back without
changes to parent or bounds. Quickstep recorded starting and finishing its
recents animation; the WM Shell trace contained no new split activation. The
stock Overview path therefore has not moved app 1 into a split root at this
point. The dumps do not describe the picker's preview views or their geometry;
they cannot be used to infer that HOME itself occupies a free column.

At Back, `CoreBackPreview` identified the focused Launcher window, and WM
Shell reported `TYPE_CALLBACK`, `mPrepareRemoteAnimation=false`, and
`triggerBack=true`. No new WM transition or split trace was recorded. HOME
remained focused fullscreen, app 1 remained a separate fullscreen task in
the background, and A/B/C stayed empty. This is Launcher-side Back handling,
not a Shell split dismissal and not evidence of how to restore an anchor
already moved into A. The exact Launcher callback method and view state are
not exposed by these dumps.

### Equal-width geometry (offline only)

`NativeThreeColumnGeometry` computes a common set of stage and divider
rectangles from the effective landscape display rectangle and divider gap in
pixels. For the observed display width and divider gap (2484 and 26 px),
the stage x ranges are `0..811`, `837..1647`, and `1673..2484` (widths
811/810/811); no density change is required. This is a tested calculation,
not a live bounds fix. The batch build reports it alongside the native bounds.
Integrate only once the same rectangles can drive
stage WCT bounds, surfaces and divider/touch geometry together.

The stock Overview picker defers placement until confirmation. Immediate
placement with a picker in the unoccupied area needs a separate selection
surface or a verified OEM path that supports partially populated layouts.
Before enabling such a path, verify that Shell can finish the enter transition
with only A populated and can dismiss that same state with A restored to
fullscreen. Do not reuse the observed two-app enter arguments for three stages
or reparent HOME based on this trace.

### Detached check: partially populated native enter

`work/NativeSplitProbe.java prepare-anchor` now initializes Shell ProtoLog in
its own `app_process`. It uses only local synthetic tokens and a detached
coordinator; it never submits a WCT or registers an organizer or handler.

- `setupActiveStagesReady(6, wct)` returned normally, set active order to
  `[A,B,C]`, changed the reported active-stage count to 3, and queued unhide
  for the three roots. This step does not set their bounds or move an app.
  `StageTaskListener.addTask` separately added the anchor reparent to A.
- With the arrival of that anchor modeled in A and no children in B/C,
  `prepareExitSplitScreen(2, 16, wct)` queued A's children back to the
  default parent on top and lowered the split root. The method then stopped
  at Motorola usage-stat initialization because the detached coordinator had
  no application context. This is a partial **unapplied** WCT, not proof of
  a completed dismiss or a successful recovery from a failed enter.
- For the candidate `EnterSession` (`extraTransitType=1004`, no remote,
  `resize=false`, entering position `6`), the probe compared independent
  coordinators with one, two, and three synthetic child changes. All used
  the same setup and a hidden, parentless test surface; neither test
  surface transaction was applied by the probe.

  | Occupied stages in synthetic transition | First observed stopping point |
  | --- | --- |
  | A | `startPendingAnimation` attempted `Toast.show()`; detached coordinator has no toast |
  | A + C | Same toast branch as A alone |
  | A + B + C | Reached `finishEnterSplitScreen`; no initialized `SplitLayout` in the model |

  No transition finish callback was observed. This comparison shows that
  the candidate native enter branches differently for partial occupancy.
  Its arguments are test inputs and the synthetic transition omits live WM
  changes, so it does not rule out all partial-occupancy entry points.

- Runtime reflection also returned `true` for
  `isStageOffscreenInSnapPosition(6, 0/2)` and `(7, 0/2)`. The method's name
  and values are additional reasons not to treat 6/7 as an equal-width snap
  merely by rewriting output rectangles. The probe has not established how
  this predicate is used during enter or layout updates.

The new batch picker deliberately does not try a partial-occupancy enter.
It follows the observed stock lifecycle of choosing first, then submitting
one transition after all apps have been chosen. The nonempty detached exit
WCT is not proof that recovery or the all-three animation works in SystemUI.

### First live batch attempt: stopped before submitting the WCT

`batch-v1` reached `activateSplit(6, -1, …, false)` and
`setupActiveStagesReady(6, …)` after the two choices, then ran
`prepareExitSplitScreen(2, 13, …)` as preparation cleanup. There was no
`startEnterTransition` for this attempt. Chrome remained focused and
fullscreen (2484×2232), all three stage roots had zero children, and Shell
reported inactive split. No task placement or live stage widths were tested.

Shell reported `isLeftRightSplit=false` on the landscape display and showed
top/bottom bounds. This is consistent with the horizontal-layout check
stopping the batch, but the old dump did not preserve the exact throw site.
In the detached direction probe, the ROM method
`checkAndRequestLeftRightSplitFor3App(tag, false)` set the horizontal flag
and orientation demand 0 both while idle and at snap 6. `batch-v2` requests
that direction before setup, saves the previous direction and demand for
idle cleanup, and preserves the failure step and cause in `dumpsys`.

### Second live attempt: horizontal, but only two layout slots

`batch-v2` reported `leftRight=false→true`. After activating A/B/C, it
stopped in `updateWindowBounds` with `IndexOutOfBoundsException: Index: 2
Size: 2`. Again no enter WCT was submitted; the anchor stayed fullscreen
and all stage roots were empty. The detached `three-stage-layout` probe
showed that `prepareSplitLayout(3)` entered the two-app layout's teardown
path, unlike `prepareSplitLayout(6)`. `batch-v3` passes the requested stage
count (`3`) to that method, keeps snap `6` for stage activation and enter,
and checks `getStageCount() == 3` before computing the window bounds. The
subsequent live test is recorded below.

The selection requires three existing tasks, not three different apps. A
single Chrome window with no other eligible tasks still needs two more
windows. The module does not create additional Chrome instances or select
individual tabs as separate tasks.

### Handoff: batch-v3 enters successfully; geometry and dividers remain

- **Live:** Shell completed `startEnterTransition(…, 1004, false, 6)` and
  `finishEnterSplitScreen`, with all three app tasks in A/B/C. On the
  2484×2232 display (420 dpi, divider gap 26 px), the task and displayed
  x ranges were `0..1105 / 1131..2236 / 2262..3367`, all at y `141..2091`.
  C extended 883 px beyond the right edge, and the task bounds left 141 px
  above and below. The user could drag A–B but not the other divider.
- **Runtime reflection / detached probe:** `SplitLayoutCarousel3Apps` keeps
  `mStageBounds`, `mStageBoundsDisplayed`, `mContentBounds`, `mDividerBounds`
  and `mDividerPositions` separately. `updateBounds$1(811, 1647)` produced
  equal cached task bounds in the model, but did not populate the displayed
  or content caches there. The explicit `updateBounds(...)` path produced
  different rectangles; replacing snap-target positions alone did not fix it.
  `isNotMovableDivider(0/1)` returned `false/true` for snap 6 and `true/false`
  for snap 7, consistent with the observed native restriction.
- **Surface and touch APIs:** `StageUISpec` carries `boundsBeforeScale`,
  `boundsAfterScale`, `matrix`, and `scaleHW`; a fresh instance starts with
  empty bounds, an identity matrix, and 100% scale. `DividerView` has an index,
  interactive state, and native `onTouch`; its `SplitWindowManager` exposes
  `setTouchRegion(Rect)`. The view's interactive flag does not replace the
  carousel's separate movement policy. No live hooks for these paths were added.
- **Saved work:** `NativeThreeColumnGeometry` now exposes `Layout.stages` and
  `fromDividers(...)`, which uses root-relative divider positions and clamps
  them to keep three positive-width columns within the effective rectangle.
  `work/NativeSplitProbe.java` contains the `carousel-metadata` and expanded
  `bounds` probes. The new geometry helper is not wired into WM Shell and was
  not built or tested in this session segment. No new APK was installed.
- **Next:** add tests for divider clamping; use the same effective rectangle
  and cuts for WCT, stage/display/content caches, `StageUISpec`, and divider
  bounds/touch regions. Verify both dividers through drag and release without
  restoring the offscreen carousel bounds. Scope changes to the managed
  horizontal three-stage group; keep standard two-app split and DPI unchanged.
  Verify on-device entry, roll, and exit with `dumpsys` and `logcat`.

### batch-v4: WM Shell geometry hooks (not yet device-verified)

- The module starts with equal columns in the horizontal Shell root, then
  feeds the same clamped cuts to the stage/display/content/divider caches
  and bounds in Shell's WCT. `StageUISpec` receives matching before/after
  bounds with the ROM's identity matrix and 100% scale. No DPI override.
- Each divider has an input region over its own gap. Drag moves only the
  grabbed cut; release asks Shell to commit the new bounds without the
  carousel snap. Cancel restores the prior preview. Hooks are scoped to the
  managed group, and a missing ROM entry point at bind keeps the native path.
- After enter, look for `batch-v4`, `fitGeometry=true`, `stageSpecUpdated=true`,
  and `touchDividers=[0, 1]` in the WM Shell dump. Compare live task bounds
  (`dumpsys activity containers`) and displayed bounds: on the recorded
  2484×2232, 420 dpi setup, expect x `0..811 / 837..1647 / 1673..2484`,
  y `0..2232`. The module's calculated fit alone is not evidence of live placement.
- Drag A–B and B–C separately to both extremes, then release. Check that the
  ungrabbed cut stays put, all three apps fit, and taps next to each divider
  reach the app. Repeat after roll; verify exit, fold/rotation, and stock
  two-app split. Check `logcat` for `NativeThreeColumn` layout errors or a
  SystemUI restart under DirtyFrag.

#### Repeat-enter freeze: exclude Shell's own roots

On a second device attempt, the Shell log showed `addTask(9519)`,
`addTask(9518)`, then `addTask(3)`. Task 3 was the split container itself,
not an app window. The device subsequently hit a system-server pre-watchdog.
In `batch-v4.1` the picker excludes the current split root and all three stage roots by
both task ID and token, and the batch refuses to prepare reparents if those
identities are incomplete, duplicated, or overlap with the selected tasks.
Verify two complete enter/exit cycles on device before testing freeform and
rotation separately. A connection between the reported freeform slowdown
and this invalid task selection has not been established.

#### batch-v4.2: retain ownership and accept rolled stage order

The live `batch-v4.1` dump showed all three original tasks still in their
stage roots after the module reported `split no longer owned`. Shell then
used stock carousel bounds again (e.g. x `-883..222` for the left column).
The last managed snapshot had both divider touch regions but no observed
`StageUISpec` rewrite. The next enter prepared the valid `[C,A,B]` stage
order and was incorrectly rejected before submitting its WCT.

`batch-v4.2` checks ownership from organizer child tokens, independently of
temporary visibility and orientation changes. On new enter it accepts a
permutation of the same three verified roots and uses their current physical
order. The existing surface transaction can also receive matching stage
position, identity matrix and crop; `surfaceFitApplied` reports that path
separately from `stageSpecUpdated`. Neither flag by itself proves touch
delivery: check all three app portions in `dumpsys input`, tap each app in
turn, and verify the bounds still fit after focus changes and on second enter.

#### batch-v4.3: stop the carousel overlay intercepting the left column

On `batch-v4.2`, task bounds, displayed bounds and both divider input strips
matched the equal-width fit. `dumpsys input` showed a separate
`Embedded{OffscreenTouchZoneTopLeft}` above the left app, with its touchable
region covering that app's entire column. `batch-v4.3` passes an empty
region for the carousel's named offscreen zones during managed horizontal
three-column tiling and restores the native region when leaving that mode.
Verify with `offscreenTouchMuted` and the live `dumpsys input` regions, then
tap each column and check both dividers and ordinary split.

`batch-v4.3` was observed with `offscreenTouchMuted=0` and the left zone
still covering the app. `batch-v4.4` tracks those exact named channels when
Shell registers them with the window session, including overlays that don't
use the windowless layout's region setter. On managed entry it marks the
tracked zones non-touchable and clears their regions; on exit it restores
their latest native flags/regions. Compare `offscreenTouchTracked` and
`offscreenTouchMuted` with the live InputDispatcher snapshot.

#### batch-v4.5: stage configuration and transition isolation

After a repeat enter, the live WCT carried a stale `850x473dp` override for
the center stage even though its new bounds were 810×2232 px at 420 dpi.
There was no retained starting window in the later dump. Also, rotation
temporarily allowed the module's landscape fit on a 2232×2484 portrait
root. The module now lets WindowManager recompute screen-size overrides
from each replacement rectangle without changing DPI. Fit is restricted to
landscape roots, and explicit resize requests are limited to module entry
and divider commits when the native callback reports no change. Native
transition animations keep control of stage surfaces outside managed fit.
Verify two entry/exit cycles, then separately repeat the same freeform action
and check app configuration, surface drawing and responsiveness. The logs
did not establish a separate bottleneck in stock freeform.
