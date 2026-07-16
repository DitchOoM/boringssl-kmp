# BoringSSL patches (RFC §12 D9 — the patch axis)

Ordered `*.patch` files in this directory are applied to the fetched `google/boringssl` source **at
the canonical pin**, with `git apply -p1`, in **filename order**, **before any cmake** (see
`:boringssl-build`'s `fetchBoringSsl` task). They are security backports / cherry-picks that ride on
top of the pin *before it moves*.

**This set is currently EMPTY** — plain `google/boringssl @` the canonical pin is expected to satisfy
the quiche `ffi,qlog` link-smoke (the boring-sys feature patches add symbols without changing the ABI
quiche depends on — RFC §10). Add a patch here only when a real need appears (e.g. the link-smoke
fails, or a security backport lands before the pin advances).

## Conventions

- **Name** patches `NN-short-description.patch` where `NN` is a two-digit order prefix (`00-`, `10-`,
  …). Filename order **is** apply order.
- Generate against the pinned tree so paths are relative to the clone root: from a checkout of the
  pinned commit, `git diff > 10-fix.patch`, applied with `-p1`.
- A patch that does **not** `git apply --check` cleanly at the pin fails the build loudly (no
  half-patched tree).

## Content-addressing & rebuilds

The ordered patch set is hashed by **(filename + bytes) of each patch, in order** into `patchSetHash`.
That hash folds into:

- **every build marker** (`.archives-…-p<hash>-…`, `.ffi-…-p<hash>-…`, `.apple-…-p<hash>-…`,
  `.android-…-p<hash>-…`) — so adding, editing, reordering, or removing a patch forces a full rebuild;
- **`provenance.json`** — as `"patchSet": "<hash>"` and `"patches": ["NN-…​.patch", …]`, so a bundle is
  fully identified by *commit + patch set*, not commit alone.

The source tree also carries a `.patchset-<hash>` marker; a hash change re-prepares it (clean checkout
+ re-apply), never leaving it half-patched. CI source/archive caches key on `patches/**`, so a patch
change busts them too.

## Version-bump policy (D9)

A Maven version is **packaging semver** (the delivery line), independent of the BoringSSL commit/patch
dimension. Map a patch change to a bump by its *effect*:

| Patch effect | Version bump | Rationale |
|---|---|---|
| Build-only (portability, warnings, build flags — no symbol/behaviour change) | **PATCH** | Same BoringSSL behaviour + same shim ABI. |
| Additive behaviour (new backported function/feature, ABI-compatible) | **MINOR** | New capability, no break. |
| ABI break / behaviour change consumers can observe | **MAJOR** | Breaking. |

The commit + `patchSet` live in the catalog/`provenance.json`/BOM — never in the version. Multiple
simultaneous commits are distinct **coordinates/variants** (D8, item 5), not versions.
