# ADR-0010: Mongolian voice prompts are played from a pre-generated segment clip pack, not from device TTS

- **Status:** proposed (2026-10-01). It is accepted only after NAV-007 AC 9 device evidence and the PO's D23 decision (backlog owed decision 7). Until then the D23 minimum (on-screen text plus a chime, NAV-005 AC 39) stays in force, and nothing in this ADR is implemented. Revised 2026-10-01 after the skeptic review of the spike: context corrected (Gemini-TTS, eSpeak NG), non-final takes for chained prompts added, and the "no device TTS for Mongolian" rule in Decision 1 now cites the PO decision D80 instead of "PO to confirm". Still proposed, because the clip pack is only the expected fallback pending NAV-007 AC 9 (D76).
- **Date:** 2026-10-01
- **Stories:** NAV-016 (draft: voice fallback implementation), NAV-005 (AC 34, 38–40), NAV-007 (AC 9–12), NAV-015 (iOS), the web demo mode (parallel run). Evidence: [spike `mongolian-voice-tts`](../spikes/mongolian-voice-tts.md).

## Context
- **Almost no device voice.** The spike found no Mongolian (`mn`) voice in any first-party engine: Apple system voices (so iOS Safari and every iOS browser too), Google Speech Recognition & Synthesis, Samsung TTS, Huawei ML Kit. RHVoice and Piper have none either. The exception is **eSpeak NG**: Mongolian was merged on 2025-05-27 (PR #2202) and ships in NVDA 2025.2 (Windows). But it is formant (robotic), GPL-3.0 (we cannot bundle it), and the official Android build predates the merge, so on phones it is at most a voice a user installs. The only browser with Mongolian voices is Edge desktop, and those are Microsoft cloud voices. This is desk evidence (spike §2). NAV-007 AC 9 confirms it per device, including whether eSpeak NG `mn` is installable.
- **The prompt set is closed.** Since ADR-0009 §3.3 and navigation-ux §4.1, every Mongolian prompt comes from a pure generator over a closed set: instruction parts (ADR-0008 keys), C4 ordinals, a distance prefix from a fixed rounding table (C3, D67), and fixed phrases (A11–A13, off-route, GPS). **No street names are spoken** in this slice (C5).
- **Cloud voices exist.** Neural Mongolian voices exist as cloud services: Azure `mn-MN-YesuiNeural`/`BataaNeural`, Chimege and others. Google Gemini-TTS lists mn-MN (Preview); the classic voice types have none. Its Preview terms, output rights and price for our case are unknown (spike §2.2). Using them **at runtime** brings network dependence while driving, per-use cost and cross-border text (D9).
- **No usable on-device model.** The only on-device open neural model, MMS-TTS `mon`, is CC-BY-NC (non-commercial). The eSpeak NG Mongolian phonemiser makes a self-trained Piper/VITS voice more feasible, but that stays out of the MVP (spike §3.3).
- **Constraints:**
  - privacy: nothing route-derived leaves the device
  - offline and tunnel behaviour (NAV-005 AC 34: a prompt starts ≤ 1 s after its trigger and is dropped after 3 s)
  - phones without Google Play services (D58)
  - a public repository (D35)
  - glossary-exact wording (NAV-007 AC 12–13)

## Decision (proposed)
1. **Fallback order for Mongolian voice:** clip pack → chime + A1 notice (pack missing, corrupt or failing to play). **English keeps device TTS** (ADR-0009 §3.4), with the chime fallback.
   - **Device `mn` TTS is not used for Mongolian once the pack ships: PO decision D80** (2026-10-01, spike §6 Q5, option "clip pack only"). The pack plays even if the phone reports an `mn` voice (for example a user-installed eSpeak NG, which is robotic), so every phone sounds the same. **Until NAV-016 ships, NAV-005 AC 38 is unchanged:** a usable device `mn` voice is used if one is found (ADR-0009 §3.4).
2. **The voice generator returns segments as well as text.** The pure generator (navigation-ux §4.1; Kotlin in NAV-005, ports for iOS and web) returns, for each prompt, the display string (unchanged; still feeds the golden set, NAV-005 AC 40) **and** an ordered list of **segment keys**. Example: `["dist.m.300", "instr.turn.right"]`, or `["dist.km.1_5", "instr.keep.left", "join.then", "instr.turn.slightRight"]`. Rules:
   - joins only at natural prosodic boundaries: after the distance prefix, and around «дараа нь»
   - numbers and units are one segment («гурван зуун метрт»), never split
   - "continue on" with n ≥ 10 km is composed from attributive numeral segments plus a tail segment
   - an instruction segment that stands as `{first}` before «дараа нь» may need a **non-final take** (continuing intonation), because the sentence-final take may sound like two sentences. The F4 listening test decides this; if needed, it adds roughly 35–45 segments per voice (spike §3.2)
   - **every generator output must map to existing segments.** A pure test enumerates the generator domain and fails on any unmapped key
3. **Pack format.** A versioned pack per voice: one audio file per segment, plus `manifest.json` with:
   - `packVersion`, `voiceId`, `source` (vendor + voice, or "recorded"), `glossaryRevision`
   - per-segment `key`, the take (final or non-final, if F4 requires both), the exact **spoken text** (spelled-out numerals), `durationMs`, `sha256`
   
   A single format that decodes natively on Android, iOS and browsers (for example AAC-LC `.m4a`, mono). The codec choice is the mobile engineer's, recorded in NAV-016. Estimated size about 1.2 MB per voice, about 1.4 MB with non-final takes (spike §3.2, estimate).
4. **Generation happens at build time, never at runtime.**
   - **Source (b1), neural.** A generator script calls the chosen TTS vendor **once per segment**, with the spelled-out text. The vendor is chosen by the F4 listening comparison.
   - **Source (b2), recorded.** The same manifest drives a recording script for a voice talent.
   - The vendor key lives in a local or CI environment variable (`.env.example` gets the variable name only). **No key in the repository.**
   - Every segment is checked by a native listener before a pack is released.
   - The script and the manifest schema belong to backend tooling (`backend/tools/voice-pack/`, owner backend-engineer). Clients consume a released pack.
5. **Distribution.**
   - **MVP:** the pack is bundled in the Android and iOS apps and, through a later web story, in the web build. NAV-017 builds no audio clips (D75). **No contract change.** `openapi.yaml` stays as it is.
   - **Licence check:** clips go into the public repository **only if** the source's terms allow public redistribution. Otherwise the pack is a private build artifact fetched at build time (PO, spike §6 Q7).
   - **Later:** downloadable packs (voice choice, smaller install) would add a gateway static path and a contract entry through a new ADR revision.
6. **Playback.**
   - Segments of one prompt play gaplessly in order, with a configurable join pause (default 80 ms, tuned in the listening test).
   - Existing NAV-005 rules are unchanged: one prompt at a time, drop after 3 s, audio focus with ducking, `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`, and mute stops playback within 1 s.
   - Web: audio is unlocked on the «Эхлэх» tap (iOS Safari needs a user gesture).
7. **Verification moves left.**
   - Prompt duration (NAV-007 AC 11) = sum of `durationMs` + joins, checked on the JVM for the whole generator domain.
   - Avoid terms and digit/abbreviation scans run over the manifest's spoken text.
   - The NAV-007 AC 10 listening test runs on the pack.

## Alternatives considered
| Option | Pros | Cons |
|---|---|---|
| **Device TTS only** (status quo) | No work; free; local | No Mongolian voice on any first-party engine (spike §2); only a user-installed eSpeak NG (robotic, GPL-3.0, not bundleable). The result would be chime-only for almost all users, so the "Mongolian voice" promise (research §7 Phase 1) is not met |
| **Runtime cloud TTS through the gateway** (`/v1/tts`, key server-side, cache) | Any text, including future street names; one implementation | Network-dependent while driving (AC 34 drops late prompts); still needs an offline fallback; contract change; per-use cost (about USD 540–575/month at 1,000 drives/day without a cache, estimate; the Azure price is from secondary sources, cross-checked against the Azure Retail Prices API, spike §2.2); route-derived text crosses the border (D9) unless a Mongolian provider is used. For a closed set, a cache converges to this ADR anyway |
| **On-device open model** (MMS-TTS, Piper, eSpeak NG, own VITS) | Offline; any text | MMS is non-commercial (excluded); Piper has no Mongolian voice and its successor is GPL-3.0; eSpeak NG has Mongolian but is formant (robotic) and GPL-3.0; our own model is an ML project with quality risk; tens of MB per voice; impractical on the web |
| **Full-utterance clips** (one file per complete prompt) | Best prosody, no joins | About 5,000 single prompts plus chained combinations explode the count (estimate). "Continue on" is open-ended in km |
| **Segment clip pack (chosen, proposed)** | Every phone and browser; offline; < 100 ms start; no runtime data flow; near-zero cost; JVM-testable; wording changes are a regeneration | Join prosody must pass the listening test; the generator gains a segment output; pack versioning tied to the glossary; vendor output terms needed; no street names (acceptable in this slice) |

## Consequences
- **Easier:**
  - Mongolian voice on iPhones (the PO's phone) and phones without GMS
  - offline and tunnel prompts
  - privacy (zero runtime traffic)
  - deterministic golden tests including duration
  - the same pack for Android, iOS and web
- **Harder:**
  - every glossary or wording change that touches voice now also **regenerates and re-listens the pack**. The pack's `glossaryRevision` must match the build, checked in CI by the mobile engineer
  - NAV-007 panel revisions should be batched
  - a human voice talent (b2) is best done after the wording freezes
- **Unchanged:**
  - the banner and notification text (ADR-0008, ADR-0009)
  - the voice schedule (ADR-0009 §3.3)
  - the English voice path
  - `openapi.yaml`
- **Follow-ups** (spike §7): F1 web voice probe, F2 NAV-007 AC 9/10 amendment, F3 spoken numeral forms (BA), F4 listening comparison and terms, F5 NAV-016 refinement, F6 acceptance of this ADR, F7 street names later.
- **Revisit triggers:**
  - spoken street names are requested (then the hybrid with runtime TTS or an on-device model gets its own ADR)
  - a first-party engine ships a usable `mn` voice on most phones sold in Mongolia
  - the pack fails NAV-007 AC 10 at the joins
