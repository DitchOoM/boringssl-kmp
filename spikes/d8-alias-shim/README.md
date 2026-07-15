# D8 alias-shim spike

**Gate:** RFC §12 **D8** (content-addressed prefixing) cannot be ratified — and **D3** ("one unprefixed
copy forever") cannot be relaxed — until the alias mechanism is proven end-to-end on **ELF and Mach-O,
including asm-defined symbols**. This spike is that proof.

## What it proves

The quiche / `boring-sys` consumer hardcodes **unprefixed** BoringSSL names in its Rust `extern "C"`
FFI, and the C-preprocessor prefix trick never reaches Rust — so a prefixed `libcrypto` won't satisfy
it as-is. D8's answer is a **generated link-time alias adapter**: build BoringSSL with
`BORINGSSL_PREFIX=b<hash8>` (content-addressed by commit), then map every plain name to its prefixed
implementation with linker aliases, so a plain-symbol consumer binds to the content-addressed copy with
**no source changes**. That lifts the invariant from "exactly one unprefixed copy" to **"exactly one
copy per distinct commit, keyed by content."**

The spike builds a real prefixed `libcrypto.a` at the canonical pin, generates the alias table, and
links + **runs** a plain-symbol consumer (`consumer.c`, a quiche/boring-sys stand-in) against it —
exercising both a C symbol (`SHA256`) and **asm** symbols (`ChaCha20_ctr32` executed; the RFC 8439
zero-key keystream `76 b8 e0 ad …`, plus `sha256_block_data_order` referenced by plain name).

## Method (`run.sh`)

1. Clone `google/boringssl` @ the canonical pin — read from `gradle/libs.versions.toml`, the single
   commit literal in the tree (no second copy).
2. Build `libcrypto.a` **unprefixed**; `util/read_symbols.go` → `symbols.txt`.
3. Build `libcrypto.a` **prefixed** with `-DBORINGSSL_PREFIX=b<hash8> -DBORINGSSL_PREFIX_SYMBOLS=…`.
   Every symbol — **including asm** (`boringssl_prefix_symbols_asm.h`) — becomes `b<hash8>_<name>`.
4. Generate the `plain → b<hash8>_` alias table from the prefixed header's symbol set ∩ what the
   archive actually defines, materialized per format:
   - **Mach-O:** `ld64 -alias_list` — lines `_b<hash>_NAME _NAME`.
   - **ELF:** an `ld` linker script passed as an additive input — lines `NAME = b<hash>_NAME;`.
5. Compile the plain-symbol `consumer.c` against the **normal** (unprefixed) headers, link it against
   the **prefixed** archive + the alias table, and run it.

```bash
# auto-detects macho on macOS, elf on Linux:
bash spikes/d8-alias-shim/run.sh
```

CI runs both formats in `.github/workflows/spike-d8.yaml` (ubuntu-24.04 = elf, macos-14 = macho).

## Result

| Format | Linker mechanism | C symbol (`SHA256`) | asm symbols (`ChaCha20_ctr32`, `sha256_block_data_order`) | Verdict |
|---|---|---|---|---|
| **Mach-O** (macos-14 / local arm64) | `ld64 -alias_list` | ✅ KAT | ✅ executed + link-resolved | **PASS** |
| **ELF** (ubuntu-24.04) | `ld` alias linker script | _see spike-d8.yaml_ | _see spike-d8.yaml_ | _pending CI_ |

Prefix at the canonical pin: `b44b3df6f`. Prefixed archive exports e.g. `_b44b3df6f_SHA256_Init`; the
plain `SHA256_Init` is gone. The plain consumer's undefined `_SHA256` / `_ChaCha20_ctr32` /
`_sha256_block_data_order` all resolve through the alias table to the prefixed impls.

**Local Mach-O run:**

```
SHA256(abc)=ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
ChaCha20_ctr32 keystream[0..3]=76b8e0ad (asm, plain-aliased)
&sha256_block_data_order=0x100dba180 (asm, plain-aliased)
PASS: plain C + plain ASM symbols resolved to the prefixed impls via the alias table
```

## Bearing on the RFC

Once ELF is green in CI, D8's core mechanic is proven and the RFC's "🔬 PROPOSED (pending spike)" on D8
can move to ratified, with D3 relaxed to the per-commit content-addressed form. The production wiring
(a `boringsslPrefix` catalog flag → prefixed build + generated adapter + distinct `-<alias>` coordinate)
is build-out **item 5**; this spike only proves the mechanism it relies on.
