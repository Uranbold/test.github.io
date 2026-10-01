# Spike: Mongolian voice guidance on phones: device TTS availability and fallback options

- **Requested by:** the PO in chat on 2026-10-01. Triage: P1 / standard, technical spike, owner architect, timebox one lane run, no production code (`docs/triage/log.md`, 2026-10-01).
- **Stories and decisions:**
  - NAV-007 AC 9–11 (device TTS evidence, listening test, prompt duration)
  - NAV-005 AC 38–39 and glossary A1 (the D23 minimum: on-screen text plus a chime)
  - [D23](../../requirements/decisions.md) and D75 (PO, 2026-10-01: the final fallback is chosen **after** real-phone voice evidence; this spike only narrows the options)
  - backlog owed decision 7
  - draft NAV-016 (voice fallback implementation)
  - NAV-015 (iOS)
  - NAV-017 (web demo mode, parallel run; D73: chime plus A1 when there is no Mongolian voice; D75: no cloud TTS and no audio clips in that story)
- **Owner:** architect. **Date:** 2026-10-01.
- **Status:** a recommendation for the PO. It **narrows the options; it does not decide** (D23). The companion [ADR-0010](../adr/0010-mongolian-voice-pregenerated-clip-pack.md) is **proposed** and becomes accepted only after the PO's decision.
- **Scope rule followed:** desk research only. Public vendor documentation, public model cards and public voice lists were read through a web fetch on 2026-10-01. **No account was created, no API key was used, no paid service was called, and no audio was generated or listened to.** No phone was available in this environment. This document does **not** replace NAV-007 AC 9 (device evidence).
- **Evidence labels:**
  - **D:** stated in the vendor's or project's own documentation (cited)
  - **M:** read by us on 2026-10-01 from a public list or page (cited), through a web fetch with a summariser, so a device or second read must confirm it
  - **I:** inferred by the architect (a reasoned estimate, not a fact)
  - **U:** unknown; the follow-up item that answers it is named

---

## 1. Question and short answer

**Question.** Can our apps speak Mongolian turn-by-turn instructions on real phones (Android, iOS) and in the web demo? If not, what are the realistic options, and how do they compare on quality, latency, offline behaviour while driving, cost, licence, privacy and effort?

**Short answer.**
1. **The team's belief is supported by desk evidence, but not yet by a device.** No first-party engine we checked lists a Mongolian voice. That covers Apple (VoiceOver and Spoken Content languages, iOS 27 page), Google Speech Recognition & Synthesis on Android, Samsung TTS, Huawei ML Kit, and the open engines eSpeak NG, RHVoice and Piper (§2). iOS Safari and every iOS browser use the Apple system voices, so they have no Mongolian voice either. The **one browser exception** is **Microsoft Edge on desktop**. It lists two Mongolian voices, «Microsoft Yesui Online (Natural)» and «Microsoft Bataa Online (Natural)». These are **cloud voices**: the browser sends the text to Microsoft (§2.3). So, unless NAV-007 AC 9 finds something we missed, **NAV-005 (Android), NAV-015 (iOS) and the web demo on the PO's iPhone can only show Mongolian text plus the chime** with device TTS. The A1 notice is therefore the expected result on almost every phone, not an edge case.
2. **Usable Mongolian voices do exist, but only as services, not on the device.** They are **Microsoft Azure** `mn-MN-YesuiNeural` and `mn-MN-BataaNeural` (D), and the Mongolian providers **Chimege** (D, terms and pricing not public), **TsetsenAI** and **Egune** (an API client library exists; terms U). **Google Cloud TTS has no Mongolian voice** (M), and **OpenAI TTS has none** (a user feature request from 2025-05, M). ElevenLabs' model page lists Mongolian only for its newest models (M, quality U).
3. **There is no on-device open-source Mongolian model we could ship commercially today.** Meta's **MMS-TTS `mon`** exists (VITS, 36.3 M parameters), but its licence is **CC-BY-NC 4.0, non-commercial** (D), so it is **excluded** for this product. **Piper has no Mongolian voice** (M), and its maintained successor `piper1-gpl` is **GPL-3.0** (flagged under our licence principle). Training our own voice is possible on CC0 (Common Voice `mn`) or CC-BY (MnTTS) data, but that is an ML project with quality risk (§3.3).
4. **Our voice text is a closed set, and that makes pre-generated clips the strongest option.** The NAV-005 voice generator (navigation-ux §4.1, ADR-0009 §3.3) speaks **no street names** in this slice. Every Mongolian prompt is built from about 35–45 instruction parts, 10 roundabout ordinals, a distance prefix from a fixed rounding table, and a few fixed phrases. That is roughly **300 audio segments per voice, about 1–2 MB** (I, §3.2). Its natural joins are after the distance prefix and around «дараа нь». A clip pack generated once at build time from a neural Mongolian voice (or recorded by a voice talent):
   - speaks on **every** phone, including phones without Google Play services (D58) and iPhones
   - works **offline** and in tunnels, and starts in well under 100 ms (I)
   - sends **nothing** off the device at runtime
   - costs close to zero to generate (about 7,500 characters per voice per regeneration, I)
   - can be **checked on the JVM** for every combination (prompt length, NAV-007 AC 11; Avoid terms; spoken numeral forms)

**Recommendation (for the PO; D23 still applies):**
- **Expected D23 choice: option (b), a pre-generated segment clip pack for Mongolian prompts, bundled in the apps and the web demo.** It is generated at build time by a script from the glossary templates (ADR-0010, proposed). The chime and the A1 notice stay as the last-resort fallback when the pack is missing or fails. English keeps device TTS: practically every Android and iOS device has an English voice.
- **Voice source for the pack: decide by a listening comparison, not by desk research.** Candidates are Azure Yesui and Bataa, Chimege, and optionally TsetsenAI or Egune, against the NAV-007 AC 10 thresholds. A **human voice talent** is the alternative if neural quality fails or the generated-audio terms do not allow public redistribution (§4). The PO approves any trial accounts. We sign up for nothing.
- **Defer runtime cloud TTS (a) and on-device models (c).** They are needed only if spoken **street names** are wanted later (glossary C5, optional). That needs its own story, ADR and privacy review (D9 cross-border).
- **Do NAV-007 AC 9 now and cheaply.** The Android NAV-005 build already logs the engine and voice it finds (ADR-0009 §3.4). For iOS, a tiny voice-list probe page in the web demo shows the PO's iPhone voice list in one minute (follow-up F1). AC 9 still needs the full device matrix before sign-off.

---

## 2. Device and browser TTS today (question 1)

### 2.1 Mobile platforms and engines

| Platform / engine | Mongolian voice? | Offline? | Evidence | Label |
|---|---|---|---|---|
| **iOS / iPadOS**, `AVSpeechSynthesizer` (system voices) | **No.** Mongolian is absent from Apple's VoiceOver, Live Speech and Read & Speak language list on the iOS 27 feature-availability page. An older device dump of `speechVoices()` (iOS 13) has 38 locales and no `mn` | n/a | [Apple iOS feature availability](https://www.apple.com/ios/feature-availability/); [speechVoices() list (gist)](https://gist.github.com/Koze/d1de49c24fc28375a9e314c72f7fdae4) | M |
| iOS third-party voice providers | iOS 17+ lets an app install voices for the whole system through a speech-synthesis provider extension. **We found no Mongolian provider app** | — | (no source found) | I, U |
| **Android: Google "Speech Recognition & Synthesis"** (default on most Play phones) | **No.** Mongolian is not in its list of 90+ supported language variants. Google's 2020 Mongolian additions were **speech-to-text** (Gboard dictation, Live Transcribe), not TTS. Search results often mix the two up | Voices download for offline use | [Wikipedia: Speech Recognition & Synthesis](https://en.wikipedia.org/wiki/Google_Text-to-Speech); [Montsame on Gboard Mongolian dictation](https://montsame.mn/en/read/220059) | M |
| **Samsung TTS** | **No.** The published lists (30+ languages, including Kazakh) do not include Mongolian | Yes | [Samsung TTS language list (third-party summary)](https://speechactors.com/article/where-is-samsung-text-to-speech) | M (secondary source) |
| **Huawei** (HMS ML Kit TTS; phones without GMS) | **No.** Six languages: en, zh, fr, es, de, it | On-device model for some | [HMS ML Kit TTS](https://developer.huawei.com/consumer/en/doc/HMS-Plugin-Guides-V1/text-to-speech-0000001052489003-V1) | M |
| **eSpeak NG** (GPL-3.0) | **No** `mn` among 127 languages | Yes | [eSpeak NG languages](https://github.com/espeak-ng/espeak-ng/blob/master/docs/languages.md) | M |
| **RHVoice** | **No.** It covers Kyrgyz, Tatar, Uzbek and others, but not Mongolian | Yes | [RHVoice languages](https://rhvoice.org/languages/) | M |
| **Piper / SherpaTTS** (sherpa-onnx engine APKs) | **No.** There is no `mn` folder in `rhasspy/piper-voices`, and no Mongolian sherpa-onnx engine APK | Yes | [piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/main); [sherpa-onnx engine APKs](https://k2-fsa.github.io/sherpa/onnx/tts/apk-engine.html) | M |
| Organic Maps / CoMaps TTS guides (OSM apps that rely on device TTS) | **No** Mongolian in any engine they list (Google, RHVoice, eSpeak, Vocalizer, Acapela, SherpaTTS) | — | [CoMaps TTS guide](https://www.comaps.app/support/tts-configuration-guide-for-android/); [Organic Maps TTS FAQ](https://organicmaps.app/faq/voice/text-to-speech-tts-and-voice-directions-on-android/) | M |
| Paid multi-language engines (Acapela, Vocalizer, "Multilingual TTS") | Not established. No Mongolian found in the public lists we could read | — | — | U (AC 9 lists what is installable) |

**Reading.** On a stock Android or iOS phone sold in Mongolia, `TextToSpeech.setLanguage(mn)` is expected to return `LANG_NOT_SUPPORTED` or `LANG_MISSING_DATA`, and iOS is expected to return no `mn` voice. NAV-005 AC 39 then gives the chime plus the A1 notice. Some engines silently fall back to another language. ADR-0009 §3.4 already rejects a voice whose locale language is not `mn`, so Mongolian is never read by a Russian or English voice. **This is still desk evidence.** NAV-007 AC 9 must record it per device, because OEM images and future engine updates can differ.

### 2.2 Cloud TTS services with Mongolian (option (a) sources, and also generators for option (b))

| Service | Mongolian voices | Notes | Evidence | Label |
|---|---|---|---|---|
| **Microsoft Azure AI Speech** | `mn-MN-YesuiNeural` (female), `mn-MN-BataaNeural` (male), both neural | Pay-as-you-go neural TTS about USD 16 per 1 M characters, free tier 0.5 M characters/month (secondary sources; the official page did not render prices for us). For real-time synthesis Microsoft states that **neither the input text nor the output audio is stored**. A Microsoft moderator says caching generated audio **inside your app** is generally acceptable but redistribution outside the app needs care. Whether **embedded (on-device) Speech** offers `mn-MN` is not listed | [Azure language support](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/language-support?tabs=tts); [Azure TTS data, privacy and security](https://learn.microsoft.com/en-us/azure/foundry/responsible-ai/speech-service/text-to-speech/data-privacy-security); [Q&A on caching and redistribution](https://learn.microsoft.com/en-us/answers/questions/5596131/azure-ai-speech-terms-on-caching-redistribution-of); [Azure pricing](https://azure.microsoft.com/en-us/pricing/details/cognitive-services/speech-services/) | D (voices, retention); M (price, caching guidance) |
| **Chimege Systems** (Ulaanbaatar) | "4 male and 4 female voices" (Chimege Reader). The company says its API is the only Mongolian TTS/STT API. Its older stack was Tacotron2/WaveGlow | **API terms, pricing, on-premise or offline options and output-licence terms are not public.** There is a developer console at `console.chimege.com`. Data would stay in Mongolia, which is good for D9 (I) | [chimege.com](https://chimege.com/en/); [old TTS page](https://old.chimege.com/tts); [mongolian-nlp list](https://github.com/tugstugi/mongolian-nlp) | D (existence), U (terms) |
| **TsetsenAI** | A Mongolian TTS platform with "multiple voices" and a developer API (site title) | Everything else U | [tsetsen.ai](https://tsetsen.ai/) | M, U |
| **Egune AI** | A TTS client library (`egune` on PyPI) with actor presets and voice cloning | Search results link the Egune Chat app to Chimege, which is not confirmed. Terms U | [egune on PyPI](https://pypi.org/project/egune/); [Rest of World on Egune](https://restofworld.org/2025/mongolia-egune-ai-llm/) | M, U |
| **Google Cloud TTS** | **None.** Mongolian is absent from the voice list, all voice types | — | [Google Cloud TTS voices](https://docs.cloud.google.com/text-to-speech/docs/list-voices-and-types) | M |
| **OpenAI TTS** | **None** (a user feature request asked for Mongolian, 2025-05-09) | — | [OpenAI community request](https://community.openai.com/t/feature-request-mongolian-language-support-in-openai-tts/1256120) | M |
| **ElevenLabs** | The models page lists Mongolian only for the newest model generation, not for v3 or the low-latency Flash models | Quality for Mongolian U; cloud only; USD pricing far above Azure (I) | [ElevenLabs models](https://elevenlabs.io/docs/overview/models) | M |

### 2.3 Browsers (`speechSynthesis`)

| Browser / OS | Mongolian voice? | Online or local | Evidence | Label |
|---|---|---|---|---|
| **Safari on iOS / iPadOS, and every iOS browser** (all use WebKit and the system voices) | **No** (same voice set as §2.1 iOS) | — | [Readium: SpeechSynthesis in browsers and OSes](https://readium.org/speech/docs/WebSpeech.html) | M |
| Safari on macOS | **No** (macOS lists about 45 languages, and Mongolian is not among them) | — | Readium (above) | M |
| **Chrome on Android** (and other Android browsers) | Only if the **Android system engine** has `mn`, which per §2.1 it does not | Engine-dependent | Readium (above) | M |
| Chrome desktop | **No** (19 Google voices in 15 languages, all online) | Online | Readium (above) | M |
| Firefox | Uses OS voices only, so **no** | — | Readium (above) | M |
| **Microsoft Edge on desktop** | **Yes:** «Microsoft Yesui Online (Natural) - Mongolian (Mongolia)», «Microsoft Bataa Online (Natural) - Mongolian (Mongolia)» | **Online** (`localService` is expected to be `false`). The text goes to Microsoft's service. WebView2 does not expose them | [WebView2Feedback #3091](https://github.com/MicrosoftEdge/WebView2Feedback/issues/3091); Readium (above) | M |
| Edge on Android / iOS | Not established; probably the system voices only (I) | — | — | U |

**Consequence for the parallel web demo mode** ([NAV-017](../../requirements/stories/NAV-017-web-demo-mode-replay.md); D73, D75). The PO tests on an **iPhone**, so the demo will find **no** Mongolian voice and will play the chime with the A1 notice (NAV-017 AC 29). That is the expected result, not a defect. NAV-017 AC 28 already does what this spike would ask:
- it matches `voice.lang` against `^mn([-_]|$)`
- it sets the voice and `lang` on every utterance
- it never passes Mongolian text to another voice

The voice text has no street names (navigation-ux §4.1). Two gaps remain:
- **Online voices.** On **Edge desktop**, AC 28 accepts «Microsoft Yesui/Bataa Online (Natural)», which are online voices (`localService === false`). The browser then sends the template text to Microsoft. That text has no coordinates, but its sequence and timing are route-derived (I). Whether that is acceptable in the demo is §6 Q4.
- **No voice-list probe.** AC 28 does not show the list of voices it found. A probe that shows it would turn the PO's iPhone into the first piece of iOS AC 9 evidence (F1).

---

## 3. Options when there is no device voice (question 2)

### 3.1 (a) Cloud TTS at runtime
The app sends each prompt text to a TTS service while driving and plays the returned audio. To keep the key off the device and the user's IP away from the vendor, this would go **through our gateway** (as in ADR-0007): for example `POST /v1/tts` with server-side key, rate limit and cache. That is a contract change (`openapi.yaml`). It also adds a new failure mode in the exact situations where guidance matters: weak mobile data on intercity roads, tunnels, the international link (NAV-008 R-items). NAV-005 AC 34 drops a prompt that cannot start within 3 s, so every network hiccup becomes a silent or chime-only prompt. It still needs an offline fallback, so it is **strictly more work than (b) for the closed template set**. The only reason to use it is open text: street names (C5) or later free-form content.

### 3.2 (b) Pre-generated or pre-recorded clips for the closed template set
**What exactly must be voiced.** This is taken from navigation-ux §4.1 and the current `mn` resources. Counts are architect estimates (I) for sizing; the generator domain is authoritative.

| Segment group | Source | Count (I) |
|---|---|---|
| Instruction parts: turns, keep, merge, ramps (ADR-0008 keys), U-turn, continue, depart ×8 compass, arrival ×3, plus walking variants | `maneuver.*` in `mn.json` (33 today) | 35–45 |
| Roundabout voice form «Тойрогт ороод {ordinal} гарцаар гарна уу» for exits 1–10 (C4), «Тойрогт орно уу», «Тойргоос гарна уу» | A10, C4 | 12 |
| Distance prefixes in metres: 30–90 (step 10), 100–950 (step 50), «метрт» | §4.1 table, C3 | 25 |
| Distance prefixes in km: «1 километрт» … «9,9 километрт» (one decimal, «,0» dropped) | §4.1, C3, D67 | 90 |
| "Continue on" «{n} километр үргэлжлүүлэн явна уу»: n = 2…9,9 as whole clips; n ≥ 10 composed from about 30 numeral clips in the **attributive** form plus a tail | A12 | 80 + ~31 |
| Fixed: approaching tail «… метрт очих газартаа хүрнэ» (reuses metre prefixes ≤ 500 m), «дараа нь», «Та маршрутаас гарлаа», «GPS дохио тасарлаа», «GPS дохио сэргэлээ» | A11, A13, AC 42, AC 51–52 | ~6 |
| **Total per voice** | | **≈ 280–320 clips** |

**Joins.** Prompts are concatenated at **natural prosodic boundaries only**: after the distance prefix ("300 метрт | баруун тийш эргэнэ үү") and around «дараа нь» ("…, дараа нь | …"). Each segment is generated as a **whole phrase**, number and unit together («гурван зуун метрт»), so no join falls inside a phrase that vowel harmony or numeral attributive forms would break. Short silences of about 50–120 ms (tuned in the listening test) hide the joins. Chained prompts stay two manoeuvres at most (§4.4), so at most four segments play in a row.

**Size.** About 300 clips × about 1.3 s average × about 24 kbit/s mono speech ≈ **1.2 MB per voice** (I). Two voices (female and male) fit in under 3 MB. The format is the mobile engineer's choice. One format that decodes natively on Android, iOS and all browsers (for example AAC-LC in `.m4a`) avoids per-platform packs.

**Why "pre-generated" beats "runtime" even with the same vendor:**
- **Text normalisation is solved once, by us.** We feed the TTS the **spelled-out** text («гурван зуун метрт», «нэг километр …») and listen to every clip. A device engine or a runtime API might read «300» in the nominative («гурван зуу метрт») or read «1,5» badly (NAV-005 R16). How Mongolian decimals are spoken is **not yet fixed in the glossary** (§6, Q6).
- **Testable without a device.** Clip durations are known at build time, so QA can check NAV-007 AC 11 (≤ 5.0 s single, ≤ 8.0 s chained) for **every** generator output on the JVM, not just on samples.
- **Wording churn is cheap.** The NAV-007 panel will revise wording. With a neural source, regeneration is a script run of minutes, and the cost is about 7,500 characters per voice, within the Azure free tier (I). With a human voice talent, every change is a new studio session, so **neural now, a human voice optionally after the panel freezes the wording**.

**Human voice talent instead of neural.** This gives the best naturalness and the cleanest licence (a work-for-hire contract assigning all rights, so we can even publish the clips). But the cost of a session in Mongolia is U, and it is slow to re-record for the panel's revisions. Keep it as option (b2).

### 3.3 (c) On-device open-source TTS model

| Candidate | Licence | Size | Quality / fit | Label |
|---|---|---|---|---|
| **Meta MMS-TTS `mon`** (VITS) | **CC-BY-NC 4.0: no commercial use, so excluded** | 36.3 M parameters (about 145 MB fp32; int8 roughly a quarter, I) | Trained on religious read speech (MMS data); script and quality for Khalkha Cyrillic not stated | D (licence, size), U (quality) |
| **Piper** | Original `rhasspy/piper` MIT; the maintained successor `OHF-Voice/piper1-gpl` is **GPL-3.0** (embeds eSpeak NG). **Flag GPL** | Voices about 20–60 MB (I) | **No Mongolian voice**, and eSpeak NG (its phonemiser) has no Mongolian | D/M |
| **Coqui TTS** | Code MPL-2.0; XTTS weights under the non-commercial Coqui Public Model Licence (I). The company has shut down (I) | — | No Mongolian release found | I, U |
| **Train our own** (VITS/Piper-style, character input) on **Common Voice `mn`** (CC0; about 49 h validated, 606 speakers) or **MnTTS** (CC-BY 4.0; about 8 h, one professional female announcer) | Data CC0 / CC-BY. Runtime **sherpa-onnx (Apache-2.0)** on Android and iOS | 20–60 MB per voice (I) | Common Voice is crowd-sourced read speech from many speakers: good for ASR, weak for a single clean TTS voice (I). MnTTS comes from Inner Mongolia University; whether its text is Cyrillic Khalkha or traditional script, and its accent, are U. Older Khalkha work exists (tugstugi DC-TTS, MIT code, about 5 h of Bible audio with unclear rights). **An ML project with real quality risk** | D (datasets, licences), I, U |

On the web an on-device model means a WASM or ONNX runtime plus tens of MB. That is unreasonable for a demo (I). **Conclusion: (c) is not viable for the MVP.** Revisit only if offline street names become a requirement.

### 3.4 (d) Hybrid
The useful hybrid is **(b) for every template prompt**, plus, **later and only if the PO wants street names**, (a) through our gateway (with cache and a Mongolian provider for data residency) or (c). Its fallback order on the device would be: clip pack → (street-name segment from runtime TTS if online, else omitted) → chime. Device TTS stays the engine for English.

---

## 4. Options table (question 3)

Quality is rated only from documentation. **Nothing here was listened to.** The NAV-007 AC 10 listening test decides quality.

| Option | Quality (Mongolian) | Latency while driving | Offline / tunnel | Cost (I unless cited) | Licence | Privacy (nothing location-derived leaves the device) | Effort Android / iOS / web (I) | Verdict |
|---|---|---|---|---|---|---|---|---|
| **Device TTS** (status quo, AC 38) | **None available** (§2.1). If an `mn` voice appears, quality U | Local, < 200 ms | Yes if the voice is local | Free | OS | Local, except the Edge desktop online voice (text goes to Microsoft) | Done (NAV-005) / port / in the parallel run | Keep for **English**; for Mongolian it gives the A1 notice plus the chime |
| **Chime + text** (D23 minimum, AC 39) | No speech | Local | Yes | Free | Own (generated tones) | Local | Done / port / in run | Last-resort fallback in every option |
| **(a) Cloud TTS at runtime via gateway** (Azure, Chimege, …) | Neural; Azure and Chimege voices exist (D); rating U | Network RTT plus synthesis, roughly 0.3–1.5 s on mobile (I); prompts dropped after 3 s (AC 34) | **No.** Needs a cache or prefetch plus a fallback | About USD 16 per 1 M characters (Azure, M). About 1,200 characters per 30-min drive gives about USD 0.02 per drive; 1,000 drives/day ≈ **USD 575/month** without a cache. A cache of the closed template set brings it near zero (which is really option (b)) | Vendor terms per call | Template text plus timing reveals the manoeuvre sequence; street names (C5) reveal the route. The gateway hides the user's IP. **Cross-border (D9)** unless Chimege or another Mongolian host is used | Gateway endpoint plus contract change M; clients M each **plus** an offline fallback | **Defer.** Only for street names later |
| **(b1) Pre-generated neural clip pack** (bundled) | Same voice as (a), with **our** text normalisation and every clip checked by listening | **Local playback, < 100 ms** | **Yes, always** | **About zero**: about 7,500 characters per voice per regeneration, within Azure's 0.5 M/month free tier (M). Chimege price U | Depends on the vendor's **output-use terms**: in-app use looks allowed (Azure, M). **Public redistribution** (public repo D35, public web demo assets) must be confirmed in writing | **Nothing leaves the device at runtime.** At build time only fixed template text goes to the vendor (no user data) | Generator script S–M (backend tooling); Android playback of segment lists M; iOS M (NAV-015); web S–M (Web Audio; iOS Safari needs a user gesture to unlock audio, so unlock on «Эхлэх») | **Recommended** |
| **(b2) Human voice-talent recordings** | Best naturalness (I) | Local | Yes | One studio session plus re-recording per wording change; amount U | **Cleanest**: work-for-hire with full assignment | Nothing leaves the device | Same client effort as (b1); recording logistics | Alternative to (b1); preferred **after** the panel freezes the wording, or if (b1) fails the listening test or the terms |
| **(c) On-device open model** | Unknown to poor without our own training (I) | Local synthesis, 0.1–0.5 s on mid-range phones (I) | Yes | Training compute plus ML effort; free runtime | **MMS = non-commercial (excluded)**; Piper successor **GPL-3.0 (flag)**; own model from CC0/CC-BY data is fine | Local | L–XL (dataset, training, sherpa-onnx integration on 2 platforms); web impractical | **Not for MVP** |
| **(d) Hybrid (b) + later (a)/(c) for street names** | (b) quality for templates; names as (a)/(c) | Template local; names network | Templates yes; names only online (a) | (b) plus the runtime cost of name segments only | As above | Street-name text to a provider: needs a privacy review; prefer a Mongolian provider | (b) now; names later as a new story | **Target shape** if the PO later wants street names |

---

## 5. What this spike did not verify
- **No device was tested.** Every "no Mongolian voice" row is desk evidence (M). NAV-007 AC 9 remains required. Desk research does not replace it (AC 9 and the PO's request).
- **No voice was heard.** Azure, Chimege, TsetsenAI and Egune quality is not rated. NAV-007 AC 10 thresholds (≥ 95 % identified, 0 left/right confusions, 0 number mispronunciations) apply to whichever source is chosen.
- **Vendor terms were not confirmed in writing.** That covers Azure output redistribution in a public repository or public web assets, and the Chimege, TsetsenAI and Egune API terms and prices. No vendor was contacted (scope rule).
- **The Azure price** comes from secondary sources, because the official page did not render prices to our fetch tool.
- **Paid Android engines** (Acapela, Vocalizer, Multilingual TTS) and **Edge on mobile** were not checked.
- **Clip-pack size and counts** are estimates (I) from the current generator rules and resources. ADR-0010 makes the generator domain authoritative.

---

## 6. Open questions (for the PO, through the orchestrator)
1. **Direction.** Accept (b) "pre-generated clip pack" as the **expected** D23 fallback, to be confirmed when NAV-007 AC 9 has device rows? Or wait for AC 9 before any NAV-016 preparation? *Recommendation:* accept it as expected, and start only the no-regret items (F1, F3, F4) now.
2. **Voice source and budget.** Should a neural pack (Azure, Chimege or another provider), chosen by a listening comparison, be generated first, with a human voice talent optional later? *Recommendation:* yes. The PO (not an agent) obtains trial or free-tier access and the written output terms.
3. **Voice gender.** One voice (female or male) or both, with a setting? This is a product decision. *Recommendation:* one voice in the first release, picked by the panel's listening scores.
4. **Online browser voices in the web demo.** Is sending glossary template text (no street names, no coordinates) to Microsoft through the Edge desktop online voice acceptable? *Recommendation:* acceptable for the demo only, or skip it and play the clip pack once it exists.
5. **Device Mongolian TTS when it appears.** If some phone later has a usable `mn` voice, should the app still prefer the clip pack? *Recommendation:* the pack first (consistent, panel-tested quality), with device TTS not used for Mongolian.
6. **Spoken numerals (BA and the NAV-007 panel).** How are distances read aloud: attributive numerals («гурван зуун метрт»), and the decimal «1,5»? Options include «нэг аравны тав», «нэг цэг тав», or rounding voice distances so that «нэг хагас» is the only fraction. The glossary C3 fixes the written form only. This affects device TTS (R16) and the clips alike.
7. **Clip licence and the public repository (D35).** Keep generated or recorded clips **out of the public repository**, as a private build artifact, unless the source's terms explicitly allow public redistribution? *Recommendation:* yes, until terms are confirmed in writing.

---

## 7. Follow-up items (go back through triage)
| # | Type | Title | Brief |
|---|---|---|---|
| F1 | change (NAV-017, in flight) | Voice-list probe and the online-voice rule in the web demo mode | Two additions to NAV-017:<br>1. A hidden diagnostics view that lists `speechSynthesis.getVoices()` (name, `lang`, `localService`, `default`), so the PO's iPhone, an Android phone and Edge desktop give AC 9 evidence in minutes. It shows the list on screen only and sends nothing anywhere.<br>2. A rule in AC 28 on online voices (`localService === false`), per the PO's answer to §6 Q4.<br>The `mn` matching in AC 28 already fits. This is mid-flight on a running story, so the default is "finish first" |
| F2 | change (NAV-007) | AC 9 records browser voice lists and the clip-pack path | Extend AC 9 with iOS Safari, Android Chrome and Edge voice lists (from F1). Make AC 10 explicitly apply to the clip pack when it is the chosen fallback |
| F3 | change (glossary / BA, NAV-007) | Spoken forms of numbers and distances in voice | Fix the spoken forms for C3: attributive numerals, decimal reading or rounding rule, km values ≥ 10 for «… километр үргэлжлүүлэн явна уу». Needed by device TTS (R16) and the clips |
| F4 | spike | Mongolian voice source listening comparison and output terms | Short-list Azure Yesui/Bataa, Chimege, TsetsenAI/Egune and a human voice sample. Generate the 2 longest prompts (navigation-ux §4.7) plus 20 coverage prompts with PO-provided trial access. Run a quick panel listening test with the AC 10 method. Obtain written terms on in-app and public redistribution and data location. No keys in the repo |
| F5 | feature (NAV-016, refine the draft) | Mongolian voice clip pack: generator, pack format, Android playback | Conditional on NAV-007 AC 9 and the PO's D23 decision. Build-time generator from the glossary templates (versioned pack with a manifest), segment-list output from the voice generator, Android playback with audio focus, chime fallback, JVM duration check for AC 11. iOS (NAV-015) and web follow on the same pack |
| F6 | adr | Accept ADR-0010 (clip pack) after AC 9 and F4 | ADR-0010 is proposed now. Accept, revise or reject after the device evidence and the listening comparison |
| F7 | spike (later, Phase 2) | Street names in Mongolian voice prompts | Covers C5 naming pattern, runtime TTS through the gateway (a Mongolian provider for data residency) vs an on-device model, a privacy review (D9), and cache design. Only if the PO wants spoken street names |
