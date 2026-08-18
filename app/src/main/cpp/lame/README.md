# LAME, vendored

`libmp3lame` and `include/lame.h` from **LAME 3.100**, unmodified, as released at
<https://sourceforge.net/projects/lame/files/lame/3.100/lame-3.100.tar.gz/download>
(sha256 `ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e`).

LAME is **LGPL-2.1-or-later** — see `COPYING`. It is built as its own static library and linked
into `libdemoreel_mp3.so`, whose source is in `../mp3.c`; the app is GPL-3.0-only, and LGPL-2.1 §3
is what makes the two compatible.

Two files are ours rather than upstream's, and both are additions rather than edits:

- `config.h` — what `configure` would have generated, written out for Android. Upstream ships only
  `config.h.in`.
- this file.

`mpglib_interface.c` is not vendored: it is the *decoder* half, and this app only encodes. Nothing
else was left out, and no upstream source file was touched — which is what keeps updating to a
later LAME a matter of dropping the new tree in.
