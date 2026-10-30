# Moto Razr Mod

**Author / 製作者: Devola-Sun**

Integrated LSPosed module for the Motorola RazrFold/Signature/Razr (Android 16 / API 36). One
settings app controls both the Motorola launcher (`com.motorola.launcher3`) and
SystemUI (`com.android.systemui`), built with Kotlin and YukiHookAPI 1.3.2.

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
.\gradlew.bat assembleDebug
```

Output:

```
app\build\outputs\apk\debug\app-debug.apk
```

The Xposed entry annotation explicitly sets the module package to
`io.github.devola_sun.motorolatweak`. Each variant's packaging/assembly checks
the generated `HookEntry_Impl.MODULE_PACKAGE_NAME` against the application ID.
This prevents KSP from shipping a `.test` identity: that identity reads empty
preferences and silently disables both clock glass and Keep large clock (and
resets other hooks to defaults), even when their saved settings are enabled.
SystemUI startup logs now include `keepLargeClock` and `clockGlass` so the
host's effective settings can be checked separately from the settings app.

## Settings screen

The app uses four bottom tabs: **ホーム / 機能 / ログ / その他**.  
The **機能** tab is the per-app hub for `com.motorola.launcher3` and
`com.android.systemui`; each card opens that app's dedicated settings screen.
The **ログ** tab reads the module's logcat output through root and can refresh
or clear the displayed view.

### Update suggestions

**その他 → アップデート → アップデートを自動確認** is enabled by default,
including for existing installs. On opening/resuming the settings app, it checks
the latest stable release of `Devola-Sun/Motorola-Tweak-Mod` on GitHub. A newer
version offers **GitHubで確認** or **あとで**; the former opens that release page.
The newest numeric stable version is selected from the 100 most recent GitHub
releases, independently of GitHub's `latest` designation and publication order.
Drafts and prereleases are excluded. Checks are cached in memory for 15 minutes
(one minute after failures) and repeated while the settings screen is in the
foreground. A different newer version is eligible for a prompt immediately after
it is fetched, even if a previous release was already suggested. The same release
can be suggested again after six hours; rotations/resumes do not spam dialogs.
**今すぐ確認 / Check now** bypasses both cache and prompt suppression, works with
automatic checks disabled, and reports up-to-date and network/API failure results.
Automatic failures remain silent. These are in-app dialogs, not background/system
notifications. Disabling the switch persists across restarts and suppresses
automatic checks and pending automatic suggestions immediately.

### Launcher

| Key | Default | Description |
|---|---|---|
| `hide_labels` | `false` | Hide icon labels |
| `icon_shape` | `SYSTEM` | `SYSTEM`, `SQUIRCLE`, `CIRCLE`, `SQUARE`, `DIAMOND` |
| `folder_preview_3x3` | `false` | Launcher folder icon previews up to 9 apps in a 3x3 grid; the opened folder uses a fixed 4x4 grid (max 16 apps) |
| `widget_free_resize` | `true` | Removes widget resize restrictions and exposes all four handles (1-cell steps) |
| `grid_rows` / `grid_columns` | `6` | Workspace grid, 4..10 |
| `square_grid_cells` | `false` | Make workspace cells 1:1 squares using the smaller available cell dimension; restart the launcher after toggling |
| `dock_icons` | `6` | Left-side dock slots, 4..10 (the right 3 recent icons are separate) |

The restart action automatically clears the icon cache when `icon_shape`
changed since the last applied value.

**フォルダをリキッドグラス調にする** keeps folder previews glass-styled and adds
an open-folder container using the shared notification/control-center optical
material. Fullscreen folders draw this container on their content background,
ignoring the stock background's intentional zero alpha; the Folder view still
owns the opening/closing fade. Ordinary folders retain their native drawable
fades. No RenderEffect is applied to the folder contents.
The fullscreen glass container is a 1:1 square based on its measured width.
Folder rows are fitted to that height without scaling icon artwork; native
opening placement also uses the square content height.
Measurement hooks target the declared `PagedView.onMeasure(int, int)` directly
(the ROM does not declare it in `FolderPagedView`), with an instance guard so
other paged views remain untouched. Glass drawing and its outline also clamp
to a square independently of measurement, preventing stale portrait bounds from
producing a tall container if a vendor grid API is unavailable.

**通知・コントロールセンター・フォルダの背景ブラー** reuses the existing
`cc_background_blur_percent` setting for all three surfaces. Folder opening and
closing use the launcher's native background-blur animation scaled by that
percentage (100 = native strength, 0 = no blur), independently of the folder
glass toggle. Changes take effect on the next folder opening. Zero-progress
cleanup and unrelated floating sheets remain native. The native all-apps folder
blur capability is no longer forcibly disabled.

**ホーム画面グリッド → グリッドのセルを正方形にする** uses square home-screen
cells for icons, folders and widget spans. The selected row/column counts are
preserved; unused space is balanced above/below and beside the grid. Dock, app drawer
and opened-folder cells retain their own layout. Fold/unfold, dragging, labels
and widget resizing still require device verification.

Settings writes now queue an automatic, coalesced LSPosed preference sync.
The scope list refreshes on resume and every five seconds while the settings
screen is visible; current LSPosed scope configuration takes precedence over
old log markers. Layout/icon construction changes still require the restart
action. Root actions try absolute `su` paths and drain command output while
running to avoid log-output pipe deadlocks. Grant this app root access in
KernelSU/SukiSU/Magisk if `su` is hidden from its process.

### SystemUI

| Key | Default | Description |
|---|---|---|
| `sb_show_seconds` | `true` | Show seconds in the status-bar clock |
| `sb_hide_battery_percent` | `false` | Hide the battery percentage |
| `sb_hide_volte` | `true` | Hide VoLTE/VoWiFi/NFC/HD rows |
| `sb_ios_icons` | `false` | iOS-style Wi-Fi/cellular icons |
| `sb_enable_custom_padding` | `true` | razr fold status-bar padding |
| `cc_swap_sliders` | `true` | Swap brightness/volume sliders |
| `cc_ios_connectivity` | `true` | iOS-style 2x2 connectivity card |
| `sb_left_order` | `clock,ongoing,notifications` | Left status bar element order |
| `sb_right_order` | `user_switcher,wifi,mobile,battery` | Right status bar element order |
| `disable_flag_secure` | `false` | Ignore FLAG_SECURE for system screenshots / screen recording |
| `lock_keep_large_clock` | `false` | Experimental: keep the stock large keyguard clock when notifications/media are present |
| `lock_clock_liquid_glass` | `false` | Translucent gradient/reflected bevel on supported lock-screen clock digits; independent of clock size, stock rendering in AOD |
| `lock_media_compact` | `false` | Shared compact layout for keyguard, notification-center and expanded Dynamic Island-style playback cards; control-center cards are excluded |
| `lock_pin_auto_confirm` | `false` | Hide device PIN confirmation and submit only at the selected length; known length mismatches stay stock |
| `lock_pin_length` | `6` | Selected numeric device PIN length, 4..16 |

**ロック画面のPIN入力 → 指定桁数で自動確定** is opt-in. Select the length of
the existing PIN, enable the switch and restart SystemUI. This does not change
the device credential. Only the AOSP `KeyguardPINViewController` path is supported;
SIM PIN/PUK and text-password controllers are excluded. Known mismatched
stored PIN lengths retain stock behavior. When metadata is unavailable (notably
for four-digit PINs or when native auto-confirm is disabled), the explicitly
selected length is used. Always match the current PIN length: a wrong selection
can cause failed attempts. Disable and restart SystemUI if misconfigured.
The confirm key becomes
invisible without shifting keypad positions. Exact-length appends call stock
verification once; deleting, resetting, short/long input and duplicate native
auto-confirm calls do not submit. Android credential checks, lockout and
failed-attempt accounting are unchanged. Disable and restart SystemUI to restore
stock behavior. Device verification is still required for 4/6-digit PINs,
incorrect PINs, lockout, reboot, user changes and fold/unfold.

**ロック画面 → 再生カードをコンパクト化** is opt-in and also controls expanded
Dynamic Island-style playback popups (no separate switch).
Restart SystemUI after changing it. Supported AOSP lock-screen playback cards use
plain dark backdrop blur (without reflective edges) when Liquid glass is off,
and the Liquid glass surface when it is on, with the same compact layout in both
cases. When blur is unavailable, a translucent tint is used instead. Notifications
keep their selected appearance. Unsupported layouts/recommendations stay unchanged. Native host size,
media actions, session binding and privacy remain in charge. Leaving keyguard or
moving the shared media card to QS restores the original appearance. The visual
match, seeking, output picker, track changes, fold/unfold and QS transitions still
require device verification.

The compact card now uses only the innermost playback backdrop to avoid stacking
glass tints in Motorola's wrapper views. Native TransitionLayout state changes are
restyled immediately during seeking. The output picker has a separate 40dp header
target, and playback/custom actions occupy non-overlapping bottom-row slots.
The output target is now 48dp; nested containers are positioned outside-in with
native pill padding/outline clipping removed and a centered, fit-scaled icon.
Media styling also runs at the draw boundary to cover late/inlined native state
updates that bypass the TransitionLayout method hooks.
Opening Modern QS over keyguard temporarily restores the stock notification
viewport/stack even when the status-bar controller remains in keyguard state.

Light-mode notification and playback Liquid glass now reuses Modern QS's dark
translucent material, rather than approximating glass with a near-clear or dense
white wash. Body colors, broad reflection, directional rim and inner edge use the
same dark optics as control-center cards, including the no-blur fallback. Standard
framework notification labels and native playback labels/icons use white content
on that dark glass. This changes material only, not the system theme or layout.
App-defined custom notification content, artwork and notification buttons are not
recolored. Native colors are restored on dark-mode/feature/host changes.
Dark-mode notification/media material is unchanged.
Light notification-shade cards also use
the native geometry-only draw path already used on keyguard, bypassing additional
vendor onDraw tint/blur passes rather than merely swapping the background drawable.
Dark-mode lock-screen cards keep their thinner tint and reduced broad reflection.
Light-mode lock-screen cards use the control-center tint/reflection, like the shade.
Both lock-screen themes retain the smaller local blur radius. Reflective edges remain intact;
the dark notification-shade body is unchanged. Compact liquid playback uses white
text/icons/tracks in both modes, matching the dark glass; plain (non-liquid)
compact playback keeps its dark background and white controls. Theme switches,
bright/dark wallpapers, grouped notifications and blur-disabled/power-saving states
still require device verification. Restart SystemUI after installing the updated APK.

With `lock_media_compact` enabled, supported expanded dynamic-notification playback
popups use the exact same layout renderer, lock-screen material and corner radius.
It restyles the existing controls in place without adding a player, rebinding the
session, or reparenting views. Only the innermost playback root is styled. The
older glass/artwork-overlay path is restored before compact styling takes ownership,
so both renderers never decorate the same card. Native state/draw-boundary hooks
also cover popup seeking and expansion without a delayed style swap. Zero-sized
transition frames retain an already applied style. Control-center ancestry always
takes precedence, even over a dynamic-host name or a popup window.
With compact mode off, the independent notification/media Liquid glass option
retains the native popup layout and its decorative artwork overlay. Unsupported
layouts remain native. Verify vendor popup recognition, open/close animations,
seeking, track changes, and transitions to keyguard/QS on the device.

**ロック画面 → ロック画面の時計 → 時計をリキッドガラス調にする** is opt-in.
Restart SystemUI from the top-right menu after changing it. An AGSL RenderEffect
on a separate glyph RenderNode shades the existing digits with translucent gradients and reflective edges;
it does not capture, blur or refract the wallpaper. Timekeeping, font, size,
position and native clock animations are unchanged. Supported targets are Moto
plugin TextViews tagged `clock_face_time`, AOSP `AnimatableClockView`, and reactive
clock digits discovered through ClockRegistry/ClockFaceLayout or identified clock hosts;
dates, battery widgets and status-bar clocks are excluded. Other/custom clock
renderers remain stock. The size/layout hook initializes separately and is unchanged
from before this feature; no View RenderEffect, layout params or clock-size flows are
modified by glass rendering. Disabling the option, entering AOD or leaving keyguard
bypasses glyph recording. Shader failures fall back to stock
rendering and are reported through ErrorLogger. Hardware acceleration is required.
Logs distinguish compiled shader, discovered clock hosts/digits and rendered frames.
The scope/policy unit tests do not exercise the GPU shader: verify appearance,
AOD transitions, clock-style/font changes, notifications, and fold/unfold on device.

The **ロック画面の時計 → 大きな時計を維持（試験的）** option supports the
reference ROM's reactive `KeyguardClockInteractor` / `KeyguardClockViewModel`
size streams, with legacy controller fallbacks. Restart SystemUI after enabling
or disabling it. Only explicitly registered clock-size/centering StateFlows are
mapped at the synchronized write boundary (including initial state seeding);
unrelated flows are passed through. Getter-only overrides are insufficient on
the reference ROM because optimized collectors read backing state directly.
Derived visibility remains stock so shade/AOD visibility policy is preserved.
It does not replace the selected
clock design or modify notification privacy, media controls, or authentication.
The earlier getter-only build logged `ClockSize.SMALL -> LARGE`, but the user
confirmed that the visible clock still became small. The revised build targets
`StateFlowImpl.updateState` and logs actual writes and stored values separately.
Notification spacing starts the stack at 37% of the current keyguard
window height. It adjusts the actual stack viewport, accounting for native top
padding when available; it does not depend on clock view IDs or ROM padding
setters. Recalculation is event-driven (layout, screen/configuration and status
bar state changes), with no pre-draw polling, tree scans or recurring timers.
Leaving keyguard, expanding the locked shade or entering AOD restores the stock
bounds. Notification rows themselves are not translated. While this layout is
active, the native scrolling flag is enabled, the keyguard display-count cap is
removed, and the scroll range includes content overflowing the smaller viewport.
Native notification privacy and gesture/fling handling are preserved. Device
testing is still needed for scrolling, media overflow and input handling.
Visual layout, widgets, and media placement still require **device verification**.
If content overlaps or the option has no effect, disable it and restart SystemUI.

Device verification still required: notifications arriving/being dismissed,
media play/pause, expanded notifications and scrolling, unlock/bouncer,
always-on display, and fold/unfold. Check `LockClock` log entries for missing
compatibility entry points; missing hooks leave the original behavior intact.

The **通知バーの並び替え** section lets you drag status bar elements within the
left or right group and move them to the other side with the ⇄ button. The
clock, notification icons, ongoing activity, user switcher, battery, Wi-Fi, and
mobile signal are listed separately; Wi-Fi and mobile are ordered inside the
status icon cluster, while the battery is placed independently. Restart
SystemUI after changing the order.

Use **SystemUIを再起動して反映** after changing SystemUI options.

**通知・メディアカード → サイレント通知を格納しない** is opt-in. It moves
the ROM's minimized notification section into the regular silent-card section
and disables minimized row presentation. Notification sound/vibration, channel
importance, DND, privacy and dismissal policies are unchanged. Restart SystemUI
after toggling; off restores stock behavior. While the unlocked shade is open,
it also releases the vendor's notification-count cap, which can otherwise keep
silent cards in the shelf even after their minimized classification is removed.
The saved cap is restored on leaving the shade or disabling the option; a fresh
native limit takes precedence. Keyguard and doze limits are not lifted by this
option. This does not prevent ordinary
off-screen notifications from entering the scrolling notification shelf.

**ロック画面のショートカット → ショートカットの背景をリキッドグラスにする**
controls the bottom-left and bottom-right shortcut backgrounds. Phone and Bluetooth
device battery widgets below the clock use their stock backgrounds regardless of
this switch. Turning the switch off restores the stock shortcut backgrounds.
Restart SystemUI after updating or toggling. Notification/playback glass remains a
separate setting. Playback backdrop blur explicitly uses a transparent fill, and compact
cards suppress native full-card visual-effect overlays without hiding controls.

### Launcher layout backup / restore

The backup section stores the launcher's `launcher*.db*` files and launcher
SharedPreferences XML in the app's external files directory:

```
Android/data/com.motolauncher.mod/files/launcher_backups/<yyyyMMdd_HHmmss>/
```

Backup stops the launcher, reads each file through root (`su -M` + `cat`), and
restarts it. Restore stops the launcher, removes the current launcher DBs,
writes the selected backup through root, fixes the launcher UID/permissions,
and restarts the launcher.

## Hooks

### Launcher

1. `BubbleTextView.setTextVisibility(boolean)` - forces `false` when
   `hide_labels` is enabled.
2. `InvariantDeviceProfile(Context)` and the 13-argument `DeviceProfile`
   constructor - writes `numColumns`, `numRows`, and `numShownHotseatIcons`.
   `DeviceProfile`'s `before` callback also updates the IDP instance passed as
   `args[1]` so cell sizes are calculated for the new grid.
3. `BaseIconFactory.createBadgedIconBitmap(Drawable, IconOptions, float[],
   boolean)` - writes a 0..100 `Path` into `IconOptions.mShape` unless the
   selected shape is `SYSTEM` (then the hook is bypassed).
4. `ClippedFolderIconLayoutRule` / `FolderGridOrganizer` / `PreviewItemManager` -
   keeps ordinary folders 1x1 on the workspace, draws up to 9 preview icons in a
   balanced 3x3 grid (with a small margin from the folder edge), and lays out the
   opened folder as a fixed 4x4 page so apps fill from the top-left and stay
   centered on screen.
5. `AppWidgetResizeFrame.setupForWidget` - removes provider resize restrictions,
   exposes all four resize handles, and allows spans from 1 cell to the current
   grid size (`widget_free_resize`).

### SystemUI

Ported from the previous `moto-statsubar` module:

- Clock seconds (`Clock`)
- Battery percentage hide (`BatteryMeterView`)
- VoLTE/VoWiFi/NFC/HD icon hiding (`StatusBarIconControllerImpl`,
  `ImsIconController`, mobile icon binders)
- iOS-style Wi-Fi/cellular drawables (`SignalIconDrawable`, `ImageView`)
- razr fold status-bar padding (`PhoneStatusBarView`,
  `CliStatusbarLinearLayout`)
- Control-center slider swap / height alignment (`QSModernPanel` and related)
- iOS-style 2x2 connectivity card and airplane-mode button
  (`QSModernFixedPanel`, `ModernFixedTileLayout`, `QSTileViewModernFixedImpl`)
- Status bar element reordering (`PhoneStatusBarView`): moves clock,
  notification icons, ongoing activity, the status icon cluster, and the user
  switcher between the start/end containers in the saved order.
- Disable Flag Secure (`android.window.ScreenCapture`): optional toggle that
  marks secure layers as capturable and reports no secure layers to the
  screenshot/recording pipeline.

All hooks are guarded with `runCatching` or YukiHookAPI's hook error handling so
a missing class on a future firmware update does not take down the launcher or
SystemUI.

## LSPosed setup

1. Install `app-debug.apk`.
2. Enable **Moto Razr Mod** in LSPosed. The manifest declares both scopes via
   `@array/xposed_scope`, so LSPosed selects them by default:
   - `com.motorola.launcher3`
   - `com.android.systemui`
3. Reboot once after installing/enabling the module so LSPosed loads it into
   both processes.
4. Change settings and use the matching restart button.

## Toolchain

- Kotlin 2.3.21 / KSP 2.3.12
- Android Gradle Plugin 8.13.2 / Gradle 8.13
- compileSdk 37 (required by YukiHookAPI 1.3.2), targetSdk 36, minSdk 36
- YukiHookAPI 1.3.2 / KavaRef 1.0.3
- AndroidX AppCompat 1.7.1 / Preference KTX 1.2.1

## License

Copyright (C) 2026 Devola-Sun

This project is licensed under the **GNU General Public License v3.0**
(GPL-3.0). See [LICENSE](LICENSE) for the full license text.

## Disclaimer

This is an unofficial, community-made modification. It is not affiliated
with, endorsed by, or supported by Motorola, Lenovo, Google, or the LSPosed
project. All product names and trademarks belong to their respective owners.

**Use at your own risk.** This module changes the behavior of the Motorola
launcher, SystemUI, and the Android framework through LSPosed hooks. Hooking
system processes can cause boot loops, UI crashes, data loss, or a device
that no longer boots. Root (or temproot) access and a recovery method such
as fastboot or a custom recovery are strongly recommended before installing.

The software is provided "AS IS", without warranty of any kind, express or
implied, including but not limited to the warranties of merchantability,
fitness for a particular purpose and noninfringement. In no event shall the
author be liable for any claim, damages, or other liability, whether in an
action of contract, tort, or otherwise, arising from, out of, or in
connection with the software or the use or other dealings in the software.

Only install builds obtained from this repository's
[Releases](https://github.com/Devola-Sun/Motorola-Tweak-Mod/releases) page.

### 免責事項

本プロジェクトは非公式のコミュニティ製 MOD であり、Motorola / Lenovo /
Google / LSPosed とは一切関係ありません。本モジュールの使用は**自己責任**
で行ってください。LSPosed によるシステムプロセスのフックは、ブートループ、
UI のクラッシュ、データ損失、端末が起動しなくなる等の症状を引き起こす
可能性があります。導入前にバックアップと復旧手段（fastboot / カスタム
リカバリ）を必ず用意してください。本ソフトウェアは無保証で提供され、
作者は一切の責任を負いません。
