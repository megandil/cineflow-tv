#!/usr/bin/env bash
# Puts mpv for Windows in app/mpv, where electron-builder bundles it (resources/mpv/mpv.exe).
# Pinned build from shinchiro/mpv-winbuild-cmake. mpv is GPLv2+: its licence and a source link go along.
set -euo pipefail
TAG=20261021
ASSET=mpv-x86_64-20261021-git-e471496c56.7z
cd "$(dirname "$0")/.."
rm -rf mpv mpv.7z
gh release download "$TAG" -R shinchiro/mpv-winbuild-cmake -p "$ASSET" -O mpv.7z
7z x -ompv mpv.7z > /dev/null
rm mpv.7z
cat > mpv/ABOUT-MPV.txt <<NOTE
dx.bi plays videos with mpv (https://mpv.io), included here unchanged.
mpv is free software under the GNU General Public License v2 or later (see the licence files here).
Source code: https://github.com/mpv-player/mpv
This build: https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/$TAG ($ASSET)
NOTE
ls mpv
