# Glass Home

A replacement home screen for generic Android smart TVs: calm, glassy, and built for a
remote. It replaces the stock tile wall with three things: your favourites, your
websites, and everything else in one tidy grid.

![Home screen](docs/home.jpg)

| Hold OK on any tile | Scroll down for every app |
| --- | --- |
| ![Menu](docs/menu.jpg) | ![Apps](docs/apps.jpg) |

These images are rendered from the real app code by the test suite (with stand-in app
icons), not mock-ups.

## What it does

- **Favorites** strip at the top. Streaming apps that are already installed are pinned
  automatically on first run; hold **OK** on any tile to pin, unpin or reorder.
- **Hide the noise.** Hold OK on an app and choose *Hide from home*. It stays installed
  but disappears. Bring it back from *Settings > Hidden apps*.
- **Websites as tiles.** *Add website* turns any address into a tile that opens in the
  built-in browser.
- **A browser you can drive with a remote.** The arrow keys glide an on-screen pointer
  (it speeds up while held), OK clicks, pushing against an edge scrolls, Back goes to the
  previous page. Sites are requested as desktop pages rather than blown-up phone pages.
  Hold OK (or press Menu) for the address bar, text size, and pinning the current site.
- **Direct mode** for sites made for TVs (for example `youtube.com/tv`): arrow keys go
  straight to the page and the browser identifies itself as a smart TV.
- **Five backdrops** (*Settings > Wallpaper*), a clock, and a network indicator.
- Tiny and light: a 260 KB app with no background services, sized for 1.5 GB TVs.
  Everything scales to the panel, so it looks the same whatever density the firmware reports.

### Remote cheat sheet

| Where | Key | Does |
| --- | --- | --- |
| Home | OK | Open |
| Home | Hold OK, or Menu | Options for that tile |
| Home | Back or Home | Jump to the top |
| Browser | Arrows | Move pointer; push an edge to scroll |
| Browser | OK | Click |
| Browser | Hold OK, hold Back, or Menu | Browser menu |
| Browser | Back | Previous page, then exit |
| Browser | CH+ / CH- | Page up / down |

## Install on the TV

The ready-built app is [`apk/glass-home.apk`](apk/glass-home.apk).

**With a USB stick (no computer tools needed)**

1. Copy `glass-home.apk` to a USB stick and plug it into the TV.
2. Open the TV's file manager / Media app, find the file and press OK. Allow
   "install from unknown sources" if asked.
3. Press **Home** on the remote. Choose **Glass Home** and **Always**.

**With ADB from a computer**

```bash
tools/tv.sh connect <tv-ip-address>
tools/tv.sh install
tools/tv.sh set-home
```

If pressing Home still opens the old launcher (some firmwares hard-wire it), find its
package with `tools/tv.sh info` and switch it off with `tools/tv.sh disable <package>`.
That is reversible with `tools/tv.sh enable <package>`. Only do this once Glass Home is
installed and opening correctly, otherwise the TV has no home screen to return to.

The same script removes preinstalled clutter for real (not just hiding it):
`tools/tv.sh list` shows package names, `tools/tv.sh disable <package>` switches one off.
Leave anything you do not recognise alone; system pieces often have unhelpful names.

## Honest limits

- **Tested on the JVM, not on your TV.** The home screen is exercised by 17 automated
  tests (focus movement, pinning, hiding, scaling at 720p/1080p and different densities),
  but nobody has run this build on the actual hardware yet. The browser's page handling in
  particular can only be proven on the device.
- **Netflix and Prime Video will still be the phone/tablet versions.** Their TV apps only
  run on devices certified by those companies, and uncertified devices are also capped at
  standard definition. A launcher cannot change that. A certified streaming stick is the
  only real fix; an air-mouse remote makes the tablet versions much easier to live with.
- The browser uses the TV's built-in web engine, so it is only as current as the firmware's.
- Updating to a build signed on another machine needs the old one uninstalled first
  (favourites are lost), because each machine has its own debug signing key.

## Build it yourself

Needs JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew assembleRelease      # -> app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest    # runs the tests and writes PNGs to app/build/screenshots
```

Plain Kotlin and Android framework views, no libraries. Layout is in
`HomeActivity.kt`, the drawing of tiles in `Views.kt`, the glass look in `Ui.kt`,
backdrops in `Wallpapers.kt`, and the browser in `BrowserActivity.kt`.
