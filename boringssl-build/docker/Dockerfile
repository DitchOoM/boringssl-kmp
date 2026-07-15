# syntax=docker/dockerfile:1
# ─────────────────────────────────────────────────────────────────────────────────────────────────
# Canonical old-glibc build environment for boringssl-kmp Linux artifacts (RFC §8a).
#
# manylinux2014 = CentOS 7 / **glibc 2.17** — below BOTH Kotlin/Native sysroot floors
# (2.19 linuxX64 / 2.25 linuxArm64), so BoringSSL compiled here references only floor-safe libc
# symbols: no `stat`@GLIBC_2.33, no `__isoc23_*`@GLIBC_2.38. The floor is then enforced by
# :boringssl-build:checkGlibcFloor<Triple>. Both linux arches use this SAME image, compiled NATIVELY
# per-arch (in CI: linuxX64 on ubuntu-24.04, linuxArm64 on ubuntu-24.04-arm — the legacy builder, no
# qemu/buildx; locally arm64-on-x64 falls back to buildx+qemu), so neither needs a cross-glibc toolchain.
#
# The image is content-addressed (tag carries a hash of this Dockerfile alone — the build scripts are
# bind-mounted at /work, not baked in) and built once, so it is a stable cache entry across builds.
# ─────────────────────────────────────────────────────────────────────────────────────────────────
ARG BASE=quay.io/pypa/manylinux2014_x86_64
FROM ${BASE}

ARG TARGETARCH=amd64
ARG GO_VERSION=1.21.13

# devtoolset-10 gcc/g++ is the default toolchain in manylinux2014. Add git/make/perl, a pinned cmake
# (via manylinux's cpython pip — deterministic), and a pinned Go (BoringSSL uses it for asm/err_data
# generation). Pinned versions keep the image reproducible => a stable cache key.
RUN yum install -y git make perl >/dev/null 2>&1 || true
RUN /opt/python/cp39-cp39/bin/pip install --no-cache-dir "cmake==3.29.6" "ninja==1.11.1.1" \
 && ln -sf /opt/python/cp39-cp39/bin/cmake  /usr/local/bin/cmake \
 && ln -sf /opt/python/cp39-cp39/bin/ninja  /usr/local/bin/ninja
RUN curl -fsSL "https://go.dev/dl/go${GO_VERSION}.linux-${TARGETARCH}.tar.gz" | tar -C /usr/local -xz

ENV PATH=/usr/local/go/bin:/usr/local/bin:${PATH}
WORKDIR /work
