"""NAV-016 voice clip pack generator (ADR-0010, docs/architecture/voice-pack/pack-format.md).

Build-time tool only: it reads the two inventory files, synthesises every entry (Chimege at build time, or the
deterministic stub), processes and encodes the clips with the pinned ffmpeg, and writes a private pack plus local
review files. Nothing here runs in the app or behind the gateway.
"""

GENERATOR_VERSION = "1.0.0"
