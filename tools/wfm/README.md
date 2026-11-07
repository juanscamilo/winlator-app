# Winlator File Manager

Simple file manager used in Winlator, by BrunoSX (MIT license, see LICENSE).
Upstream: https://github.com/brunodev85/wfm

Vendored here with fixes:
- Search no longer crashes (closes the window) on file names of 64+ characters
  (stack buffer overflow in the search task).
- Paths deeper than MAX_PATH are truncated instead of aborting the process.

Built by the GitHub Actions workflow with MinGW-w64; the result replaces `wfm.exe`
inside `app/src/main/assets/rootfs_patches.tzst`.
