<div align="center">

<img src="./karipap.png" alt="Karipap logo" width="320"/>

# Karipap

### A savory take on a frontend fork of Cannoli.

![License](https://img.shields.io/badge/license-GPL--3.0-eac889)
![Fork](https://img.shields.io/badge/forked%20from-Cannoli-555555)
![Platform](https://img.shields.io/badge/platform-Android-555555)

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/syach1)

</div>

---

## What is Karipap?

Karipap is a customized frontend based on Cannoli, adapted for my own usage, preferences, and setup.

It keeps the spirit of a lightweight Android retro gaming frontend while adding my own flavor, structure, and changes.

---

## Credits

Karipap is a modified fork of Cannoli.

Upstream Cannoli:

- GitHub: https://github.com/CannoliHQ/cannoli
- Website: https://cannoli.dev

## Current Focus

- Android frontend for ROM libraries, external emulators, and bundled libretro cores.
- Less opinionated ROM folder handling.
- No automatic creation of platform/game/BIOS subfolders.
- Explicit ROM and BIOS directory settings.
- First-run setup asks for storage permission, ROM directory, and BIOS directory.
- Practical built-in libretro improvements for handheld/frontend use.

## Changes In This Fork

- Added a separate `BIOS Directory` setting under Library.
- First-run setup now lets the user choose ROM and BIOS directories instead of silently assuming pre-made folders.
- First-run setup and boot create the selected Karipap root, ROM directory, and BIOS directory, and attempt to mark those folders PC-writable where the storage filesystem allows it.
- Built-in libretro BIOS lookup now scans the selected BIOS folder and its subfolders.
- Firmware is staged into the app cache using core-declared BIOS names so cores can find files even when the user's folder layout or file casing differs.
- Built-in libretro JNI exports now match the Karipap package namespace.
- Disabled automatic creation of ROM, BIOS, and per-game subfolders.
- Scanner now detects games by file/content where possible instead of requiring exact folder names.
- Scanner is limited to the selected ROM directory and subfolders, not the whole storage root.
- Fixed case/alias duplicate issues such as `GB` vs `gb`, `psx` vs `PlayStation`, `sfc` vs `SNES`, `megadrive` vs `Genesis`, and other common frontend folder names.
- Platform menus strip known platform folder aliases so games show directly instead of being hidden behind an extra alias folder.
- Fixed PlayStation games under a `psx` folder showing as a nested `psx` folder in the PlayStation menu.
- Added and fixed built-in libretro analog handling, including PlayStation BIOS detection for PCSX-ReARMed.
- Built-in libretro core options are applied before core init so PCSX-ReARMed can honor BIOS boot-logo settings at startup.
- PCSX-ReARMed built-in launches default to real BIOS auto-detection with the PlayStation startup logo enabled; SwanStation fast boot is disabled by default.
- The native core-option cache reflects startup overrides so the in-game menu shows the active values.
- Upstream Cannoli auto-update endpoints are disabled by default so fork builds do not offer upstream APK updates.
- Added more in-game video scaling options for built-in libretro cores.
- Added `Show FPS` to in-game shortcut settings and fixed fast-forward FPS reporting.
- Added artwork scraping from the selected ROM directory only, with local cover caching.
- Moved `Refresh Library` to `Settings > Advanced`.
- Tuned launcher analog-stick menu navigation to reduce accidental double-scrolls.
- Set JetBrainsMono Nerd Font as the default bundled UI font.

## Artwork Scraper

Artwork scraping is ROM-driven: Karipap only offers scraper entries for platforms that have ROMs in the selected ROM directory. Sources are attempted as local-cache downloads rather than permanent hotlinks where allowed:

- Libretro thumbnails
- TheGamesDB API
- DSESS-style HTML selector scraper

Respect upstream source terms, robots.txt, rate limits, and attribution requirements.

## Storage Compatibility

Fresh setups use a Karipap root by default and ask for explicit ROM and BIOS folders.


## Credits

Karipap is based on Cannoli by CannoliHQ. Third-party libraries, bundled cores, fonts, and shaders retain their original licenses and credit entries in the app.

## License

This fork follows the upstream license terms in `LICENSE` and preserves third-party license obligations for bundled dependencies, cores, shaders, and assets.
