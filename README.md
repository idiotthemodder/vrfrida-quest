# vrfrida Quest app

A 2D Quest app that does what `vrfrida.py` does, but on the headset with no PC:
pick a game → it builds the bridge, pushes plugins / sounds / patterns into the game,
launches it, waits N seconds after the game process appears, then injects bridge + menu with `frida-inject` (as root).

Needs: Magisk root (accept the su prompt on first open). SELinux permissive helps.
**Without root** the app switches to no-root mode: games are still found automatically, and **Patch + install** puts
Frida Gadget into the game itself (see below).

## Look
The app is drawn like UniversalMenu: same themes, widgets and animated backgrounds (spiral, sky, blossoms, night sky,
aurora, ..., character pattern). `gen_menu_assets.py` runs the menu's own theme + background code in frida at
build time and saves the colors / generated textures into the apk, so it stays in step with UniversalMenu.ts.

- **Games**: pick a game, set the delay, Launch + inject / Inject now / Unload.
- **Menu Settings**: edits the selected game's `imgui_plugins/settings.json` (theme, size, background, pattern,
  laser, pointer, sounds, saving...). Only the `menu` block + autoSave/remember change; mods and plugin values are
  kept. Saved automatically ~1s after a change and before any launch. If the menu is running in that game it can
  overwrite the file, so unload first.
- **Auth spoof**: spoofs root in `com.oculus.appsafety` (same hooks as `~/thing.sh`) so games that check device
  integrity at startup still authenticate on a rooted headset. On by default per game; `Launch + inject` / `Inject now`
  arm it automatically. A detached root watcher injects the eternalized spoof (`assets/appsafety_spoof.js`) whenever
  appsafety (re)spawns, so it survives game restarts. Buttons: `Spoof appsafety now` / `Stop auth spoof`. Log: `logs/appsafety.log`.
- **Customize** (the app itself, `applook` pref): theme presets (Auto / Meta / UXpatcher / Violet / Rose / Emerald /
  Sunset / Ocean / Crimson / Gold / Mono / custom hue) in Dark / AMOLED / Light, navigation (rail left / right, tabs top /
  bottom, labels on/off), UI size, corner roundness, font, animated app background (aurora, particles, waves, synthwave
  grid, or the synced menu background), motion on/off + speed, UI sounds (4 packs synthesized at runtime by `UiSfx.java`,
  no audio files), custom cursor (dot / ring / crosshair / arrow / reticle / diamond, size, color, trail), boot animation
  (orbit / glitch / pulse / off). "Copy style code" / "Paste style code" shares a look as a `vrstyle:` string.
- **Tools** (root): VR runtime props (refresh rate, CPU / GPU level, render resolution, fixed foveation; apply to apps
  started afterwards, reset on reboot), Wi-Fi ADB on :5555, proximity sensor off, SELinux, screenshot to
  `/sdcard/vrfrida/captures/`, force stop / clear cache / process info for the selected game, storage use + cleanup,
  root shell with output.
- **App Look**: app theme = the selected game's menu (theme + background follow whatever game you pick),
  UXpatcher (its bg / accent / text colors through the menu's colorsFromRGB), or any menu theme with the app's own background.

`NOLIB=1 ./build.sh` makes `vrfrida-emu.apk` without frida-inject, for checking the ui on an x86_64 emulator.

## No-root mode (Frida Gadget)
Same patch as `../nonroot/patch_apk.py`, done on the headset (`NoRoot.java`, `ApkPatch.java`, `ElfPatch.java`):
- every apk of the game (base + splits) is copied raw (no recompressing, ~seconds per GB) and re-signed with
  `patchkey/` (apksig from build-tools, dexed into the app). **Keep `patchkey/`**: repatching with the same key installs
  as an update and keeps the game's data; a new key means uninstalling again.
- added to `lib/arm64-v8a/`: `libfrida-gadget.so` (`dl/frida-gadget.so`, official 17.27.0), its config, `libvrfrida-loader.js.so`
  and `libvrfrida-agent.js.so` (bridge + this game's plugins embedded + menu, built at patch time).
- `libmain.so` gets `libfrida-gadget.so` as a DT_NEEDED (new segment for the grown .dynamic/.dynstr, made from PT_NOTE).
- the loader waits for libil2cpp.so + Menu Delay, then loads the first readable of: `Android/data/<pkg>/files/frida/menu.js`
  (PC kit `--push-only`), `/sdcard/vrfrida/agents/<pkg>.gadget.js` (**Update mods** writes it; only if the game can read /sdcard),
  the copy in the apk.
- first install: confirm dialog → OBB moved aside → system uninstall dialog → system install dialog → OBB back.
  Needs "Install unknown apps" allowed for vrfrida (the app opens that page).
- games with `extractNativeLibs=false` (uncompressed libs) get a warning: Gadget may not find its config there.

## App updates (GitHub Releases)
Log tab -> Updates: set `owner/repo` (default from `update_repo.txt` at build time), Check for updates / Update.
The app also checks by itself every 6 h. A release tag `v<N>` newer than the installed versionCode with a `vrfrida.apk`
asset gets offered; the apk must be signed with `vrfrida.keystore` (checked before installing). A `menu.js` asset
replaces `/sdcard/vrfrida/menu.js` only if it's newer than the local one. The repo must be public.
    ./release.sh "what changed"     # bumps version.txt, builds, gh release create v<N> vrfrida.apk menu.js

## Build / install
    ./build.sh              # -> vrfrida.apk  (no gradle: aapt2 + javac + d8 from ~/Android/Sdk)
    ./build.sh install      # build + adb install -r
    python ../vrfrida.py --sync   # push the latest menu, plugins, sounds, patterns, bridges to /sdcard/vrfrida

The apk also carries a copy of the menu, plugins, patterns and sounds from build time, unpacked on first run
(only into empty folders), so it works even if you never run --sync.

## What lives where on the headset
| Path | What |
|---|---|
| `/sdcard/vrfrida/menu.js` | compiled UniversalMenu.ts (from --sync; else the copy bundled in the apk) |
| `/sdcard/vrfrida/plugins/` | all plugins; the `// game:` line picks which get copied into each game (same rules as vrfrida.py) |
| `/sdcard/vrfrida/sounds/`, `sounds/<pkg>/` | any audio. converted on the headset (MediaCodec) to 24 kHz mono wav in `.sound_cache/` |
| `/sdcard/vrfrida/patterns/` | menu background patterns |
| `/sdcard/vrfrida/bridges/<pkg>/` | patched bridge + meta.json. same format as Mods/bridges, rebuilt on the headset when the game updates |
| `/sdcard/vrfrida/agents/<pkg>.js` | bridge + menu in one file (what frida-inject loads) |
| `/sdcard/vrfrida/logs/<pkg>.log` | injector + menu console output (shown in the app) |

## How it works
- bridge: Java port of il2cpp-scan + il2cpp-patch (`Bridge.java`), byte-identical output to the toolkit.
  Games that export real `il2cpp_*` names get the plain bridge.
- inject: a detached root shell (`run/<pkg>.sh`) waits for the game pid, sleeps the delay, runs
  `frida-inject -p PID -s agents/<pkg>.js -R v8`. It outlives the app (Quest pauses 2D apps when a game opens).
  **Unload menu** kills it, which unloads the script.
- `dl/frida-inject` is the official frida 17.24.3 android-arm64 release, packed as `lib/arm64-v8a/libfrida-inject.so`.
- signing key: `vrfrida.keystore` (keep it, or updates need an uninstall first).
