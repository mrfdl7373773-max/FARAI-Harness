# Third-party components

## PRoot (app/src/main/jniLibs/*/libproot*.so)

Prebuilt from https://github.com/green-green-avk/build-proot-android (fork of https://github.com/proot-me/proot,
statically linked with talloc). PRoot is licensed under the GNU General Public License v2.0; talloc under LGPL-3.0.
Source: https://github.com/green-green-avk/proot

## Alpine Linux

Not bundled. The minirootfs is downloaded at runtime from https://dl-cdn.alpinelinux.org. Alpine packages carry
their own licenses.

## Geist and Geist Mono (fonts)
- Source: https://github.com/vercel/geist-font
- License: SIL Open Font License 1.1 (licenses/Geist-OFL.txt)
- Used as the app typeface (app/src/main/res/font).

## Web search engine logic
- core/search follows the design of FreeSearch API (https://github.com/Ethos6/free-search-api), rewritten in Kotlin.
- License: MIT, Copyright (c) 2026 Ethos 6 (licenses/FreeSearch-MIT.txt).
