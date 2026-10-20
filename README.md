# Moto Razr Mod

**Author / 製作者: Devola-Sun**

Integrated LSPosed module for the Motorola Razr (Android 16 / API 36). One
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

## Settings screen

The app uses four bottom tabs: **ホーム / 機能 / ログ / その他**.  
The **機能** tab is the per-app hub for `com.motorola.launcher3` and
`com.android.systemui`; each card opens that app's dedicated settings screen.
The **ログ** tab reads the module's logcat output through root and can refresh
or clear the displayed view.

### Launcher

| Key | Default | Description |
|---|---|---|
| `hide_labels` | `false` | Hide icon labels |
| `icon_shape` | `SYSTEM` | `SYSTEM`, `SQUIRCLE`, `CIRCLE`, `SQUARE`, `DIAMOND` |
| `folder_preview_3x3` | `false` | Launcher folder icon previews up to 9 apps in a 3x3 grid; the opened folder uses a fixed 4x4 grid (max 16 apps) |
| `widget_free_resize` | `true` | Removes widget resize restrictions and exposes all four handles (1-cell steps) |
| `grid_rows` / `grid_columns` | `6` | Workspace grid, 4..10 |
| `dock_icons` | `6` | Left-side dock slots, 4..10 (the right 3 recent icons are separate) |

The restart action automatically clears the icon cache when `icon_shape`
changed since the last applied value.

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

The **通知バーの並び替え** section lets you drag status bar elements within the
left or right group and move them to the other side with the ⇄ button. The
clock, notification icons, ongoing activity, user switcher, battery, Wi-Fi, and
mobile signal are listed separately; Wi-Fi and mobile are ordered inside the
status icon cluster, while the battery is placed independently. Restart
SystemUI after changing the order.

Use **SystemUIを再起動して反映** after changing SystemUI options.

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
