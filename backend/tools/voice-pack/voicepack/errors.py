"""Error classes; each carries the CLI exit code of task B10 (0 OK, 2 config, 3 inventory, 4 vendor, 5 audio, 6 output)."""


class VoicePackError(Exception):
    exit_code = 1


class ConfigError(VoicePackError):
    """Token, path, ffmpeg, API base or option problem. Raised before any request is sent."""

    exit_code = 2


class InventoryError(VoicePackError):
    """An inventory input breaks a text rule (pack-format §2.2) or the TSV format. 0 requests are sent."""

    exit_code = 3


class VendorError(VoicePackError):
    """The synthesiser failed (HTTP error after the retry policy, bad WAV, token rejected)."""

    exit_code = 4


class AudioCheckError(VoicePackError):
    """A clip failed a processing or decode check (pack-format §4, §5; ADR-0010 §4.3)."""

    exit_code = 5


class OutputError(VoicePackError):
    """The output directory exists (without --replace) or could not be written atomically."""

    exit_code = 6


class PackInvalid(VoicePackError):
    """`voicepack verify`: a pack failed a validation check. `reason` is the pack-format §8 reason code."""

    exit_code = 1

    def __init__(self, reason: str, detail: str):
        super().__init__(f"{reason}: {detail}")
        self.reason = reason
        self.detail = detail
