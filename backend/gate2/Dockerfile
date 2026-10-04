# syntax=docker/dockerfile:1
# NAV-020 Gate 2 engine image (ADR-0017 §4, Amendment A1 F1 and items 1-2; NAV-020 AC 10, 12, 35).
#
# What it is: a host (x64-linux, static) build of the SHIPPED valhalla-mobile 0.6.3 code, unmodified:
#   wrapper   github.com/Rallista/valhalla-mobile   tag 0.6.3 = commit b47ad5a9aa5d907df329bd2a0bfcc9080220c9d8
#   Valhalla  its src/valhalla submodule             commit e2f017b16080f49203de245a211b09efab09cf72 (= upstream tag 3.6.3)
#   vcpkg     builtin-baseline of src/vcpkg.json      commit f176b58f35a75f9f8f54099cd9df97d2e2793a2e
#   options   the wrapper's src/CMakeLists.txt (tools/data tools/python/http/services/tests OFF, C++20, Release)
#   compiler  clang 21 (the published AAR's x86_64 library was built with Android clang 21.0.0)
# Our only code is driver.cpp (stdin/stdout JSON lines). No patch, sed or file replacement touches fetched sources.
# NEVER replace this image with ghcr.io/valhalla/valhalla:* (evidence only, ADR-0017 A1 item 1).
#
# Build (once per pin change, on a host with >= 20 GB free; about 6-12 GB scratch and 45-90 min on 4 vCPU):
#   make -C backend gate2-image                    # = docker build -t navmn-gate2:0.6.3 backend/gate2
#   make -C backend gate2-image GATE2_VARIANT=upstream   # A1 item 2 fallback if the wrapper target fails on Linux
#   docker builder prune -f                        # free the build cache afterwards (RUNBOOK NAV-020 section)
# Move: docker save navmn-gate2:0.6.3 | gzip > gate2.tar.gz ; docker load < gate2.tar.gz   (about 0.3-0.6 GB)
#
# STATUS 2026-10-04: specified, NOT built (dev container has 3-4 GB free disk; task file §4.3, §4.4).

ARG BASE_IMAGE=ubuntu:24.04@sha256:534baea6a22c03a63003dbc8dbe78fe34bc0d7e595d9a9dc9834884ff530eb55

FROM ${BASE_IMAGE} AS build
ARG WRAPPER_COMMIT=b47ad5a9aa5d907df329bd2a0bfcc9080220c9d8
ARG VALHALLA_COMMIT=e2f017b16080f49203de245a211b09efab09cf72
ARG VCPKG_BASELINE=f176b58f35a75f9f8f54099cd9df97d2e2793a2e
ARG LLVM_VERSION=21
ARG GATE2_VARIANT=wrapper
ENV DEBIAN_FRONTEND=noninteractive VCPKG_DISABLE_METRICS=1
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gnupg git cmake ninja-build \
        pkg-config zip unzip tar make autoconf automake autoconf-archive libtool python3 zlib1g-dev bison flex \
    && curl -fsSL https://apt.llvm.org/llvm-snapshot.gpg.key | gpg --dearmor -o /usr/share/keyrings/llvm.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/llvm.gpg] http://apt.llvm.org/noble/ llvm-toolchain-noble-${LLVM_VERSION} main" \
        > /etc/apt/sources.list.d/llvm.list \
    && apt-get update && apt-get install -y --no-install-recommends clang-${LLVM_VERSION} lld-${LLVM_VERSION} \
    && rm -rf /var/lib/apt/lists/*
ENV CC=clang-${LLVM_VERSION} CXX=clang++-${LLVM_VERSION}

# The two pinned source trees. The assertion fails the build if the submodule is not the pinned Valhalla commit.
RUN git clone --filter=blob:none https://github.com/Rallista/valhalla-mobile.git /src/vm \
    && git -C /src/vm checkout --detach "${WRAPPER_COMMIT}" \
    && git -C /src/vm submodule update --init --recursive \
    && test "$(git -C /src/vm rev-parse HEAD)" = "${WRAPPER_COMMIT}" \
    && test "$(git -C /src/vm/src/valhalla rev-parse HEAD)" = "${VALHALLA_COMMIT}" \
    && test -z "$(git -C /src/vm status --porcelain)"
RUN git clone --filter=blob:none https://github.com/microsoft/vcpkg.git /opt/vcpkg \
    && git -C /opt/vcpkg checkout --detach "${VCPKG_BASELINE}" && /opt/vcpkg/bootstrap-vcpkg.sh -disableMetrics

COPY CMakeLists.txt driver.cpp /build/gate2/
RUN cmake -G Ninja -S /build/gate2 -B /build/out \
        -DCMAKE_TOOLCHAIN_FILE=/opt/vcpkg/scripts/buildsystems/vcpkg.cmake \
        -DVCPKG_MANIFEST_DIR=/src/vm/src -DVCPKG_TARGET_TRIPLET=x64-linux \
        -DCMAKE_BUILD_TYPE=Release -DWRAPPER_SRC=/src/vm/src -DNAV_GATE2_VARIANT="${GATE2_VARIANT}" \
        -DCMAKE_EXE_LINKER_FLAGS="-fuse-ld=lld-${LLVM_VERSION}" \
    && cmake --build /build/out --target gate2-driver -j"$(nproc)" \
    && test -z "$(git -C /src/vm status --porcelain)" \
    && clang++-${LLVM_VERSION} --version | head -1 > /build/out/compiler.txt

FROM ${BASE_IMAGE}
ARG WRAPPER_COMMIT=b47ad5a9aa5d907df329bd2a0bfcc9080220c9d8
ARG VALHALLA_COMMIT=e2f017b16080f49203de245a211b09efab09cf72
ARG VCPKG_BASELINE=f176b58f35a75f9f8f54099cd9df97d2e2793a2e
ARG LLVM_VERSION=21
ARG GATE2_VARIANT=wrapper
ARG VALHALLA_MOBILE_VERSION=0.6.3
ARG DEFAULT_JSON_SHA256=e7a841f697ccdada70de68aa0bbea665040052bfbefffe003d8a4f55169515a9
COPY --from=build /build/out/gate2-driver /usr/local/bin/gate2-driver
COPY --from=build /build/out/compiler.txt /usr/local/share/nav-gate2/compiler.txt
LABEL nav.gate2.variant="${GATE2_VARIANT}" \
      nav.gate2.valhalla_mobile_version="${VALHALLA_MOBILE_VERSION}" \
      nav.gate2.wrapper_commit="${WRAPPER_COMMIT}" \
      nav.gate2.valhalla_commit="${VALHALLA_COMMIT}" \
      nav.gate2.vcpkg_baseline="${VCPKG_BASELINE}" \
      nav.gate2.compiler="clang ${LLVM_VERSION}" \
      nav.gate2.default_json_sha256="${DEFAULT_JSON_SHA256}" \
      org.opencontainers.image.description="NAV-020 Gate 2 engine: valhalla-mobile ${VALHALLA_MOBILE_VERSION} host build (driver only)"
USER 65534:65534
ENTRYPOINT ["/usr/local/bin/gate2-driver"]
CMD ["/data/config.json"]
