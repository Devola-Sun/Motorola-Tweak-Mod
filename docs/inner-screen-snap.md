# Inner-screen alignment (opt-in)

## Existing architecture and integration

- `HookEntry.kt`: `IYukiHookXposedInit` + `@InjectYukiHookWithXposed`; KSP generates the real LSPosed entry and `assets/xposed_init`. The new `InnerScreenSnapHook` is loaded only in `loadSystem`, after the existing reference-device/safe-mode gate. CorePatch and its UID guards are unchanged.
- `HookConfig.kt`: shared keys, defaults, and `InnerSnapMode`. The enabled and auxiliary double-tap switches default to false; the selected target defaults to BOTH.
- `SettingsStore.kt`: direct SharedPreferences-compatible XML writes, shared-preference mirroring, and `onChanged`. No second preference file or direct bootstrap-time `XSharedPreferences` instance is added.
- `MainActivity.kt` / `activity_main.xml`: framework-page SwitchCompat and Spinner; changes use the existing root-assisted LSPosed prefs sync. A signature-permission-protected broadcast requests a refresh after sync; a single delayed refresh accommodates the existing preference cache.

## WM and UI behavior

Reflection matches method names and assignable parameter types, not exact firmware signatures. Observers cover Letterbox layout/destruction, task bounds/configuration/removal, activity visibility/configuration, display focus/configuration, ATMS resize/focus, and posture changes. Android 14 `Letterbox*` and newer `AppCompat*` policy/configuration class names are candidates; absent classes are logged once and skipped.

WM snapshots and task mutations run on `WindowManagerService.mH` under `mGlobalLock`. They require an OPEN/HALF_FOLDED posture, default display ON, an unlocked interactive device, a display smallest dimension of at least 600dp, a visible focused top standard activity, and a safe margin of at least 48dp. Unknown posture, focus, safe bounds, or APIs suppress controls. Home, recents, split-screen, cover/external displays, and keyguard are excluded.

Fullscreen letterboxed activities receive per-activity horizontal multipliers 0 / 0.5 / 1 through scoped policy hooks. Global AppCompat/Letterbox configuration is only overridden during the eligible policy invocation, not for unrelated apps. Clicking requests `recomputeConfiguration()` and WMS traversal. Freeform tasks use `setBounds(Rect)` while preserving size and Y (Y is clamped only when out of safe bounds); oversized windows are not resized to fit.

All UI uses `com.android.server.UiThread.getHandler()`. Small `TYPE_SYSTEM_ALERT` windows carry NOT_FOCUSABLE, NOT_TOUCH_MODAL, and LAYOUT_IN_SCREEN. They never cover the app: each side needs a complete 48dp-wide gap. Each visible side has a directional button and a direct center button (`↔`), vertically stacked. Stable display insets are respected. UI updates are coalesced and identical snapshots do not recreate windows. A click re-resolves focus before moving anything.

No full-margin touch overlay is installed. The auxiliary switch enables the native horizontal letterbox double-tap callback where supported; within the eligible letterbox its default movement is suppressed when this switch is off. Native double-taps use the same three-step movement path as buttons. Single taps are not intercepted. Existing native behavior outside this feature's eligible activity is untouched.

Screen/configuration/user-unlock/close-dialog events remove the overlays before refresh. Dragging, resizing, changing tasks, and closing the device request new snapshots. Every hook callback, posted task, receiver, UI operation, and reflection boundary catches Throwable; failures log once to XposedBridge and ErrorLogger. A UI-add failure disables UI for the process lifetime rather than repeatedly failing in system_server.

## Compatibility and verification boundaries

The existing app still has **minSdk 36 (Android 16)**. Candidate hooks for Android 14/15 API shapes are defensive compatibility code, not a claim that this APK installs on those versions. No global minSdk or existing framework safety policy was changed.

Build and JVM geometry tests cannot verify Hello UI private field names, the native double-tap callback, system-window permissions/z-order, actual letterbox recomposition, or device-state reporting. Unsupported APIs fail closed and emit logs; test these on the intended firmware before enabling the option routinely. No device installation or reboot is performed by this change.

### Device acceptance checklist (manual, CLI diagnostics only)

1. Enable the module's Android scope and reboot through the user's normal procedure. Keep this option OFF and confirm stock behavior first.
2. Unfold, enable letterbox-only mode, focus an aspect-ratio-constrained app; test left, center, right, IME focus, normal app touches, and optional native double-taps.
3. Select freeform-only mode; verify width/height/Y preservation, cutout/navigation safe bounds, dragging, resizing, and opposite-side center control at either edge.
4. Verify BOTH mode, fullscreen/home/recents transitions, screen OFF/ON, lock/unlock, and folding to the cover display. No overlay should remain above those screens.
5. Disable while buttons are visible and confirm removal after preference sync. Inspect LSPosed logs / Errorlog for `InnerScreenSnap`; missing APIs must result in no controls, not a crash.

JVM tests in `InnerSnapGeometryTest.kt` cover all alignments, safe insets, Y clamping, size preservation, oversized-window rejection, and minimum-gap containment.

## Hello UI compatibility fix (revision 2)

Connected-device CLI diagnostics found AliExpress in fullscreen mode with actual
activity/app bounds `[584,0][1648,2484]` on the `2232x2484` inner display, while
`areBoundsLetterboxed=false` and `isLetterboxRunning=false`. The original AOSP
letterbox-only predicate therefore incorrectly rejected it even with the setting
enabled. Revision 2 recognizes this vendor compatibility case from the actual
horizontal bounds. It uses an activity-scoped `resolveOverrideConfiguration`
after-hook to translate the freshly resolved bounds/app bounds, keeping their
size and Y unchanged and rolling back a partial setter failure. It does not
pretend the Motorola path is native Letterbox or intercept its unrelated input.

Additional fallback paths support `DisplayRotation.mFoldController.mDeviceState`
(the connected device reports OPEN) and raw InsetsState safe-inset calculation
when `DisplayPolicy.getStableRect` is unavailable. Recomposition tries both the
zero-argument and boolean `recomputeConfiguration` shapes.

After loading the updated module into system_server, `adb shell dumpsys window`
includes an `InnerScreenSnap: revision=2 ...` status line. This distinguishes a
missing/stale hook from a disabled option, incompatible posture, absent focus,
missing safe-inset APIs, or a UI failure without collecting screenshots.

## Embedded panes and landscape margins (revision 3)

Connected-device diagnostics confirmed Settings activities have
`isEmbedded=true` and activity mode `multi-window`, despite their parent Task
being fullscreen. Revision 3 requires matching supported **activity and task**
windowing modes and rejects embedded TaskFragments. It does not use a package
blacklist, so a real freeform Settings window can still be supported.

The landscape Touhou game (`jp.co.cave.touhou`) was confirmed at
`[0,432][2484,1800]` on a `2484x2232` logical display with
`areBoundsLetterboxed=true`. Its empty regions are **top/bottom**, not left/right.
Revision 3 selects the available axis: horizontal margins keep left/center/right;
vertical-only fullscreen margins use top/center/bottom (`▲`, `↕`, `▼`). Vertical
policy/configuration multipliers and native double-tap callbacks are hooked with
the same scoped exception protection. Freeform movement remains horizontal.
The vendor override translation supports either axis without resizing. Complete
activity bounds, rather than content-only app bounds, avoid mistaking normal
system-bar insets for letterbox space.

Views are retained by side and moved with `updateViewLayout` when only geometry
changes. Configuration/preference broadcasts request a refresh without first
removing every view. Obsolete UI work renders the most recent snapshot instead
of hiding it. Screen-off and close-dialog events still hide immediately, and
embedded/fullscreen/noneligible snapshots still remove controls. Rotation or axis
changes invalidate pending clicks and previous alignment overrides; the next
snapshot reconstructs the correct control orientation. Coalesced refreshes retain
one pending follow-up when an event occurs during recomposition.

The dumpsys marker is now `revision=3` and includes horizontal/vertical multiplier
hook counts and the selected axis. Additional JVM regression cases cover Settings
embedded panes, the measured landscape game bounds, normal fullscreen insets,
vertical snapping, both-axis safe placement, and unchanged horizontal behavior.
Loading the new hooks and verifying interaction on the device still requires the
user's normal temproot-preserving soft reboot.
