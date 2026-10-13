# NAV-016 voice-pack generator reference image (ADR-0010 §3.3, §4; task B6/B10).
#
# What is pinned (SEC-4A BE-8: every image as tag@sha256):
#   Python    python:3.11-slim-bookworm (multi-arch index digest below)
#   ffmpeg    7.0.2, the static build of mwader/static-ffmpeg:7.0.2 (multi-arch index digest below); only /ffmpeg is
#             copied. It is the encoder ADR-0010 §3.3 measured (aac encoder delay 1,024 frames, bit-exact output).
#   jsonschema and its dependencies: requirements.txt, installed with --require-hashes.
# Byte identity of a pack (NAV-016 AC 13) is guaranteed only within this image.
#
# The source is NOT copied in: the repository is mounted read-only at /repo by backend/tools/voice-pack/run.sh, so
# the image never holds the code, the token, the cache or a pack, and it is never published (licence: ffmpeg is a
# GPL build used only as a build tool, ADR-0010 Consequences).
#
# Build:  make -C backend voice-pack-image
#   Behind a TLS-intercepting proxy: VOICE_PACK_BUILD_ARGS='--network host --build-arg HTTPS_PROXY --secret id=pip-ca,src=<ca.pem>'
#   (the CA file is a build secret: it is not stored in any layer).
#   Where the default registry is rate-limited, a mirror can be used without changing the content, because the
#   digest stays the same: --build-arg PYTHON_IMAGE=<mirror>/library/python:3.11-slim-bookworm@sha256:<same digest>.

ARG PYTHON_IMAGE=python:3.11-slim-bookworm@sha256:0a310eeecf4e1f5a0743f9a6520c90c88d089c903ca5fd283f501e3a805f5f89
ARG FFMPEG_IMAGE=mwader/static-ffmpeg:7.0.2@sha256:d9b22f65c2049d73bf8ae556bb4c5c7d45e9fb85939d87951ed98f22b0f19105

FROM ${FFMPEG_IMAGE} AS ffmpeg

FROM ${PYTHON_IMAGE}
COPY --from=ffmpeg /ffmpeg /usr/local/bin/ffmpeg
COPY requirements.txt /tmp/requirements.txt
RUN --mount=type=secret,id=pip-ca,required=false \
    if [ -s /run/secrets/pip-ca ]; then export PIP_CERT=/run/secrets/pip-ca; fi; \
    pip install --no-cache-dir --disable-pip-version-check --require-hashes --no-deps --only-binary :all: \
        -r /tmp/requirements.txt \
    && rm /tmp/requirements.txt \
    && ffmpeg -hide_banner -version | head -1 | grep -q '^ffmpeg version 7\.0\.2 ' \
    && python -c "import jsonschema"
ENV PYTHONPATH=/repo/backend/tools/voice-pack \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    NAV_VOICE_PACK_FFMPEG=/usr/local/bin/ffmpeg \
    HOME=/tmp
LABEL org.opencontainers.image.title="navmn-voice-pack" \
      org.opencontainers.image.description="NAV-016 voice clip pack generator runtime (python 3.11 + ffmpeg 7.0.2 + jsonschema); source mounted at run time"
USER 65532:65532
WORKDIR /repo/backend/tools/voice-pack
CMD ["python", "-m", "voicepack", "--help"]
