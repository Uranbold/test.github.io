# Glossary (owner: business-analyst)

> **This glossary is binding for all agents.** It fixes **one** approved Mongolian term per concept. Every agent must use these terms in UI copy, voice prompts, banner text, resource files (`mn`, `en`), screen specs, docs and tests.
>
> - **Missing term?** Don't invent one in your own area. Add it under `requests_to_other_agents` → `business-analyst`, and the BA adds it here first.
> - **Status.** Every Mongolian term below is a **proposal** (`needs native review`) until a native-speaker panel approves it under [NAV-007](stories/NAV-007-mongolian-voice-native-review.md). Until then, use the proposed term anyway so there is one consistent string to review and replace. When a term is approved or revised, the BA updates the Status column and the change log, and owners of affected resource files and tests are notified.
> - **Language policy (PO-approved):** app UI, voice, map labels, issue forms, triage comments and PO-facing summaries are Mongolian first (English second). Agent instructions, code, API, commits, ADRs and test plans are in English. Stories and AC are in English and quote user-facing Mongolian exactly, e.g. «300 м-т баруун тийш эргэнэ үү».
> - **Valhalla reference:** "Valhalla" in the Notes column compares with `locales/mn-MN.json` at the pinned version **3.9.0** (ADR-0002). **match** = we use the same wording. **deviate** = we use different wording, and the reason is given.

## Status values
| Status | Meaning |
|---|---|
| `needs native review` | BA proposal. Use it, but expect it to change after NAV-007. |
| `approved YYYY-MM-DD` | Approved by the NAV-007 panel. Change it only through a change request. |
| `revised YYYY-MM-DD` | The panel replaced the proposal. The new wording is in the Mongolian column. |
| `n/a (internal)` | Not user-facing, so no Mongolian term is needed. |
| `n/a (not translated)` | Must stay in its original form in every locale. |

## 1. Language conventions (apply to every row)
| # | Convention | Rule | Notes | Status |
|---|---|---|---|---|
| C1 | **Register of instructions** | Voice and banner instructions use the polite imperative **-на уу / -нэ үү / -но уу / -нө үү**, following vowel harmony: «эргэнэ үү», «явна уу», «орно уу», «гарна уу». Buttons use the verbal noun in **-х**: «Хайх», «Эхлэх», «Дуусгах». | Matches the PO example «300 м-т баруун тийш эргэнэ үү». Valhalla: **deviate**. Valhalla mixes bare imperatives («эргэ», «яв», «гар»), which sound curt, with -аарай («хийгээрэй»), -х («эргэх») and -нэ («эргэнэ»). | needs native review |
| C2 | **Left/right vs east/west** | In Mongolian, «зүүн» means both *left* and *east*, and «баруун» means both *right* and *west*. **Relative** directions always take «тийш» (or «талаа», «талд», «эгнээ»): «зүүн тийш». **Cardinal** directions always take «зүг»: «зүүн зүг», «хойд зүг». A bare «зүүн»/«баруун» never appears in voice. | Valhalla: **deviate**. Valhalla uses bare «зүүн»/«баруун» for both, so «Зүүн руу чиглүүл.» (start) can be heard as *head left* when it means *head east*. | needs native review |
| C3 | **Distances** | **Banner:** digits + abbreviation + hyphenated locative, e.g. «300 м-т», «1,5 км-т». **Voice:** the unit is spelled out, e.g. «300 метрт», «1,5 километрт». Voice text never contains «м», «км» or «км/ц» after a number. Under 10 m: «10 метрээс бага». | Voice avoids abbreviations because TTS may read them letter by letter. Decimal separator: comma («1,5») proposed. The reviewers confirm comma or point. Valhalla: approach alert «<LENGTH>, <CUE>» gives «300 метр, баруун эргэ.» → **deviate** (we add the locative «-т»). «10 метрээс бага» → **match**. | needs native review |
| C4 | **Ordinals (roundabout exits, khoroo numbers)** | **Banner:** «1-р», «2-р», … **Voice:** spelled out, with harmony: «эхний», «хоёр дахь», «гурав дахь», «дөрөв дэх», «тав дахь», «зургаа дахь», «долоо дахь», «найм дахь», «ес дэх», «арав дахь». | Valhalla `ordinal_values` «1-р»…«10-р» → **match** for banners, **deviate** for voice, because TTS may read «2-р» as "хоёр эр". Alternative «хоёрдугаар» kept for review. | needs native review |
| C5 | **Street and place names in instructions** | Insert names as they are. Avoid hyphen-attached case suffixes on names in voice («<НЭР>-р», «<НЭР>-д»), because the suffix must follow the name's vowel harmony and TTS may read the hyphen. Prefer wording where the name stands alone or takes a separate postposition. The final pattern is set in NAV-007. | Valhalla: **deviate**. Valhalla attaches «-р», «-д», «-с», «-н» with a hyphen and picks «руу»/«рүү» regardless of the name's vowels. | needs native review |
| C6 | **Time and duration** | Clock time is 24-hour, «14:35». Duration on banners is «1 ц 25 мин». In voice it is «1 цаг 25 минут». | | needs native review |
| C7 | **Speed** | Banner and UI: «60 км/ц». Voice: «цагт 60 километр». | «км/ц» is the standard Mongolian abbreviation (цаг = hour). Avoid «км/ч» (Russian). | needs native review |
| C8 | **Map label and name fallback** | `name:mn` → `name` → `name:en` (CLAUDE.md rule 7). | Applies to voice as well as labels. | n/a (internal) |

## 2. Navigation: route and guidance status
| English term | Approved Mongolian (UI/voice) | Definition (English) | Notes | Status |
|---|---|---|---|---|
| Route | маршрут | The path the app computes from origin to destination for one travel mode. | Keep «зам» for the physical road or street, never for the computed route. Valhalla: n/a (no generic term). | needs native review |
| Alternative route | өөр маршрут | A second or third route offered in preview. | Rejected: «хувилбар маршрут» (sounds bureaucratic). | needs native review |
| Navigation (active guidance) | навигаци | The turn-by-turn mode with voice, banners and camera following the user. | Loanword in wide use. Rejected: «замчлал» (unfamiliar to drivers). | needs native review |
| Start navigation (button) | Эхлэх | Starts active guidance from route preview. | Alternative for review: «Явах». | needs native review |
| End navigation (button) | Дуусгах | Stops active guidance. | Avoid «Зогсоох», which drivers can read as "stop the car". | needs native review |
| Get directions (button) | Маршрут гаргах | Builds a route to the selected place. | Rejected: «Чиглэл», because чиглэл = heading/direction and clashes with C2. Alternative for review: «Зам заах». | needs native review |
| Route preview | маршрут харах | Screen showing the route(s), distance and time before starting. | | needs native review |
| Origin / starting point | эхлэх цэг | Where the route starts. Defaults to «Миний байршил». | Alternative for review: «гарах цэг». | needs native review |
| Destination | очих газар | The final place of the route. | Valhalla uses both «зорьсон газар» and «очих газар». We **match** «очих газар» and drop «зорьсон газар» so that there is one term. | needs native review |
| Waypoint / stop | дайрах цэг | An intermediate stop on the route. UI: «Дайрах цэг нэмэх». | Rejected: «зогсоол» (in UB it means a parking lot). «завсрын цэг» is kept as a formal alternative for review. Valhalla: n/a. | needs native review |
| Arrival (voice) | «Та очих газартаа ирлээ» | Spoken and shown when the destination is reached. Side variants: «Таны очих газар баруун талд байна» / «Таны очих газар зүүн талд байна». | Valhalla `destination` 0 «Таны зорьсон газарт очлоо.» → **deviate** (term and wording). Valhalla `destination` 2 «Таны очих газар <RELATIVE_DIRECTION> талд байна.» → **match**. | needs native review |
| Approaching destination (voice) | «<N> метрт очих газартаа ирнэ» | Alert before arrival. | Valhalla `destination_verbal_alert` «Та зорьсон газраа ирнэ.» → **deviate** (term, and distance added per C3). | needs native review |
| ETA (arrival time) | хүрэх цаг | Estimated clock time of arrival, derived from route `duration`. Banner: «Хүрэх цаг 14:35». | Alternative for review: «ирэх цаг». Valhalla `arrive` «Очих: <TIME>.» (transit only) → **deviate**. | needs native review |
| Remaining time | үлдсэн хугацаа | Time left to the destination, e.g. «25 мин». | | needs native review |
| Remaining distance | үлдсэн зай | Distance left to the destination, e.g. «12,4 км». | | needs native review |
| Off-route | маршрутаас гарсан | The user has left the route beyond the SDK threshold. Voice: «Та маршрутаас гарлаа». | Avoid «төөрлөө» (implies being lost and blames the driver). Valhalla: n/a (client-generated). | needs native review |
| Reroute / recalculating | маршрут дахин тооцоолох. Status: «Маршрутыг дахин тооцоолж байна» | A new route is requested from the current position after off-route. | Rejected: «шинэ зам хайж байна» (uses зам for route, see "Route"). Valhalla: n/a. | needs native review |
| Lane | эгнээ | A traffic lane. Voice (Phase 2): «Зүүн эгнээнд орно уу». | Term from the Mongolian traffic rules. Avoid «шугам» (= road marking line). Valhalla: n/a (no lane phrases in narrative). Depends on OSM `turn:lanes` coverage. | needs native review |
| Speed limit | хурдны хязгаар | The legal maximum speed for the current road, e.g. «60 км/ц». | «хурдны дээд хязгаар» is correct but long, so we avoid it in UI. Depends on OSM `maxspeed` coverage. Valhalla: n/a. | needs native review |
| Voice guidance | дуут заавар | Spoken instructions during navigation. | Rejected: «дуут хөтөч». | needs native review |

## 3. Navigation: manoeuvres (banner and voice)
Examples show the voice form. Banners use the same words with C3/C4 abbreviations.

| English term | Approved Mongolian (UI/voice) | Definition (English) | Notes | Status |
|---|---|---|---|---|
| Turn left / turn right | «зүүн тийш эргэнэ үү» / «баруун тийш эргэнэ үү» | A normal turn at a junction (about 45° to 135°). Banner: «300 м-т баруун тийш эргэнэ үү». | Valhalla `turn` «<RELATIVE_DIRECTION> эргэ.» → **deviate**. We add «тийш» (C2) and use the polite form (C1). Avoid a bare «зүүн эргэ». Alternative for review: «зүүн гар тийш». | needs native review |
| Slight left / slight right (bear) | «бага зэрэг зүүн тийш эргэнэ үү» / «бага зэрэг баруун тийш эргэнэ үү» | A shallow turn (about 10° to 45°). | Valhalla `bear` «<RELATIVE_DIRECTION> чигт яв.» → **deviate**, because «чигт» is vague and clashes with cardinal directions. Alternative for review: «зүүн тийш хазайна уу». Valhalla `bear` 2 has a typo, «Үргэлжүүлэн» (should be «Үргэлжлүүлэн»), which is reported under NAV-007. | needs native review |
| Sharp left / sharp right | «огцом зүүн тийш эргэнэ үү» / «огцом баруун тийш эргэнэ үү» | A turn of more than about 135°. | Valhalla `sharp` 0 «Огцом <RELATIVE_DIRECTION> эргэ.»: we **match** «огцом» and **deviate** on C1/C2. Valhalla `sharp` 1 uses the wrong verb «гарга» ("take out"), which is reported under NAV-007. | needs native review |
| U-turn | «буцаж эргэнэ үү». Banner noun: «Буцаж эргэх» | A 180° turn to reverse direction. | Valhalla `uturn` «<RELATIVE_DIRECTION> буцаж эргэлт хийгээрэй.»: we **match** «буцаж эргэ-» and **deviate** by dropping the direction word (Mongolia drives on the right, so U-turns are to the left) and using C1. Avoid «U-turn хийнэ үү» and a bare «эргэлт хийнэ үү». | needs native review |
| Continue / go straight | «чигээрээ явна уу» | Carry on along the current road through a junction. | Valhalla `continue` «Үргэлжлүүл.» → **deviate**. Valhalla keep direction «чигээрээ» → **match**. | needs native review |
| Continue on (after a manoeuvre) | «<N> километр үргэлжлүүлэн явна уу» | Post-manoeuvre prompt stating how long to stay on the road. | Valhalla `post_transition_verbal` «<LENGTH> үргэлжлүүл.» → **deviate** (C1). | needs native review |
| Keep left / keep right / keep straight | «зүүн талаа барина уу» / «баруун талаа барина уу» / «чигээрээ явна уу» | At a fork, stay on the left, right or middle branch. At forks the long form «Салаанаас зүүн талаа барина уу» is allowed. | Valhalla `keep` «Салаанаас <RELATIVE_DIRECTION> яв.» → **deviate** on the verb and C1. We **match** «салаа» (fork). | needs native review |
| Merge | «замд нийлнэ үү». With side: «зүүн талаас замд нийлнэ үү» | Join a road from a slip road or where two roads converge. | Valhalla `merge` «Нийл.» / «<RELATIVE_DIRECTION>-р нийл.»: we **match** the verb «нийл-» and **deviate** on C1, because «зүүн-р» is ungrammatical. | needs native review |
| Roundabout | тойрог | A circular junction. | Valhalla «Тойрог» → **match**. Avoid «цагираг» and «эргэлт». | needs native review |
| Enter roundabout and take exit N | «Тойрогт ороод хоёр дахь гарцаар гарна уу». Banner: «Тойрог: 2-р гарц» | Instruction to enter a roundabout and leave by the Nth exit. | Valhalla `enter_roundabout` 1 «Тойрог руу ороод <ORDINAL_VALUE> гарцаар гар.»: we **match** the structure and «гарцаар гар-», and **deviate** with a spelled-out ordinal (C4), C1 and «тойрогт». Valhalla 3.9.0 `enter_roundabout_verbal` 3, 6, 11 and 14 are **English**, and 10 has a broken placeholder `<ORDINAL VALUE>`. Both are reported under NAV-007. | needs native review |
| Exit (roundabout or motorway) | гарц | The branch by which you leave a roundabout or motorway. | Valhalla «гарц» → **match**. «гарц» also means crossing (see "Pedestrian crossing"). Context makes the meaning clear. | needs native review |
| Exit the roundabout | «тойргоос гарна уу» | Leave the roundabout. | Valhalla `exit_roundabout` «Тойрог замаас гар.» → **deviate** (shorter, C1). | needs native review |
| Ramp / slip road | орох зам (on-ramp) / гарах зам (off-ramp) | A connector road onto or off a motorway or flyover. | Valhalla `ramp` «налуу зам» ("sloped road") → **deviate** (proposal). UB drivers rarely say «налуу зам». The panel must confirm. | needs native review |
| Distance prefix | «<N> метрт …» / «<N> километрт …» | The distance in front of an upcoming manoeuvre. See C3. | Valhalla `approach_verbal_alert` → **deviate** (see C3). | needs native review |
| Then (chained instruction) | «дараа нь» | Joins two close manoeuvres into one prompt: «… эргэнэ үү, дараа нь …». | Valhalla `verbal_multi_cue` «Дараа нь» → **match**. | needs native review |
| Head <direction> (start) | «хойд зүг рүү явна уу» | The first instruction, giving a cardinal heading. | Valhalla `start` «<CARDINAL_DIRECTION> руу чиглүүл.» → **deviate** («зүг» per C2, C1). Cardinal set: хойд, зүүн хойд, зүүн, зүүн өмнө, өмнө, баруун өмнө, баруун, баруун хойд + «зүг». Valhalla uses «өмнөд». The panel confirms «өмнө зүг» or «өмнөд зүг». | needs native review |

## 4. Search
| English term | Approved Mongolian (UI/voice) | Definition (English) | Notes | Status |
|---|---|---|---|---|
| Search | Хайх (button, placeholder), хайлт (noun) | Find places and addresses by text. Placeholder: «Газар, хаяг хайх». | | needs native review |
| Address | хаяг | A postal-style location. Mongolian order: дүүрэг → хороо → гудамж/байр → тоот. | Depends on OSM `addr:*` coverage (business risk). | needs native review |
| Place (POI) | газар | Any named point of interest or location. UI: «Газар хадгалах». | Keep «цэг» for map points (эхлэх цэг, дайрах цэг). | needs native review |
| Nearby | ойролцоо | Close to the user or the map centre. UI: «Ойролцоох». | Alternative: «ойр орчмын» (formal). | needs native review |
| No results | «Илэрц олдсонгүй» | Shown when search returns an empty list (NAV-001 AC 23). | Avoid «Үр дүн байхгүй» (calque). | needs native review |
| Recent searches | «Сүүлд хайсан» | List of the user's previous queries. | | needs native review |
| My location | «Миний байршил» | The device's current position. | | needs native review |
| Home / Work (saved places) | Гэр / Ажил | Shortcuts to saved places. | «Гэр» means home and is unrelated to "гэр хороолол" (ger district). Both are natural in context. | needs native review |
| Transliteration | галиг (галиглах) | Writing Mongolian Cyrillic in Latin letters. There is no single standard: "Sukhbaatar", "Suhbaatar" and "Sükhbaatar" all mean «Сүхбаатар». | Search must accept both scripts (NAV-003). | needs native review |
| Autocomplete (search-as-you-type) | — (behaviour, no UI label) | Search results returned for a partial query (prefix), e.g. `Сүхб` → Sükhbaatar Square. | | n/a (internal) |
| Reverse geocoding | «Энд юу байна?» (UI action) | Coordinates → nearest address or place ("what is here?"). | The UI label is a proposal. The technical term stays English. | needs native review |

## 5. Map
| English term | Approved Mongolian (UI/voice) | Definition (English) | Notes | Status |
|---|---|---|---|---|
| Map | газрын зураг | The map view. | Avoid «карт» (dated Russian loan). | needs native review |
| Layers | давхарга | A menu to toggle map overlays. UI: «Газрын зургийн давхарга». | | needs native review |
| Traffic (layer) | Түгжрэл | An overlay of road congestion (Phase 3). | «замын хөдөлгөөн» = road traffic in general (as in «Замын хөдөлгөөний дүрэм»). Alternative for the layer label: «Замын хөдөлгөөн». Avoid «траффик». | needs native review |
| Satellite | Хиймэл дагуул. Layer: «Хиймэл дагуулын зураг» | An aerial imagery basemap. | Depends on an imagery licence (not in the current stack). | needs native review |
| Recenter | Төвлөрүүлэх | Returns the camera to follow the user's position. | Alternative for review: «Миний байршил руу буцах». | needs native review |
| North up | «Хойд зүг дээшээ» | The map orientation with north at the top. The opposite is heading up, «Явах чиглэл дээшээ». | | needs native review |
| Night mode | шөнийн горим | A dark map and UI theme. Others: «өдрийн горим», «автомат». | | needs native review |
| Zoom in / zoom out | томруулах / жижигрүүлэх | Map zoom controls (also accessibility labels). | | needs native review |
| Compass | луужин | Compass control that resets bearing. | | needs native review |

## 6. Settings
| English term | Approved Mongolian (UI/voice) | Definition (English) | Notes | Status |
|---|---|---|---|---|
| Settings | Тохиргоо | The app settings screen. | | needs native review |
| Voice on / off | «Дуут заавар»: асаах / унтраах. Mute icon: «Дууг хаах» | Toggles spoken guidance. | See "Voice guidance". | needs native review |
| Avoid tolls | «Төлбөртэй замаас зайлсхийх» | Route option to avoid toll roads and toll points. | Toll points exist at UB city exits. Depends on OSM `toll=*` tagging. | needs native review |
| Avoid unpaved roads | «Шороон замаас зайлсхийх» | Route option mapped to Valhalla costing `exclude_unpaved`. | «шороон зам» (dirt road) is what drivers say. «хучилтгүй зам» is formal (alternative). «засмал зам» = paved road. Depends on OSM `surface=*` coverage (business risk in ger districts and the countryside). | needs native review |
| Units | «Хэмжих нэгж»: Километр / Миль | Distance units. The default is km. | | needs native review |
| Language | «Хэл»: Монгол / English | The UI language switch. | Each language name is shown in its own language. | needs native review |
| Travel mode | Машин / Явган | Tabs for costing `auto` / `pedestrian`. | Taxi drivers use «Машин» (`auto` or `taxi` costing, architect's call). | needs native review |

## 7. Errors and permissions
| English term | Approved Mongolian (UI/voice) | Definition (English) | Notes | Status |
|---|---|---|---|---|
| GPS signal lost | «GPS дохио тасарлаа» | Shown and spoken when location fixes stop during navigation. | Alternative: «GPS дохио алга». TTS pronunciation of "GPS" is tested in NAV-007. "GPS" is the only allowed Latin token in voice. | needs native review |
| GPS signal restored | «GPS дохио сэргэлээ» | Location fixes have resumed. | | needs native review |
| No connection | «Интернэт холболт алга» | The device has no network, so routing and search cannot be reached. | Spelling «интернэт» vs «интернет» is confirmed by the editor. Alternative: «Сүлжээ алга» (mobile network). | needs native review |
| No route found | «Маршрут олдсонгүй» | The routing service returns "no route", e.g. outside coverage or with every road excluded (NAV-001 AC 19, 32). | | needs native review |
| Location permission | байршлын зөвшөөрөл. Rationale prompt: «Байршлаа ашиглахыг зөвшөөрнө үү» | The OS permission to read device location. | The OS dialog is localised by the OS. Only our rationale text uses this. | needs native review |
| Location services off | «Байршил тогтоох үйлчилгээ унтарсан байна» | Device-level location is disabled. | | needs native review |
| Try again | «Дахин оролдох» | Retry button. | | needs native review |
| Generic error | «Алдаа гарлаа» | An unexpected failure. | | needs native review |

## 8. Places and domain terms
| English term | Approved Mongolian (UI/voice) | Definition (English) | Notes | Status |
|---|---|---|---|---|
| Aimag | аймаг | Province of Mongolia (21 aimags plus the capital). | | needs native review |
| Soum | сум | Sub-division of an aimag. Relevant to intercity and countryside routes. | | needs native review |
| Düüreg (district) | дүүрэг | One of Ulaanbaatar's 9 city districts, e.g. Sükhbaatar, Chingeltei, Bayanzürkh. | Common abbreviations such as «СБД», «БЗД», «ХУД», «БГД», «ЧД», «СХД» must be accepted in search (NAV-003). Never use them in voice. | needs native review |
| Khoroo | хороо | Sub-district of a düüreg, and the main unit in Mongolian addresses (district → khoroo → building/apartment or ger plot). | Voice ordinal per C4: «1-р хороо» → «нэгдүгээр хороо» or «нэг дэх хороо» (the panel decides). | needs native review |
| Ger district | гэр хороолол | Peri-urban UB area of ger (yurt) and detached-house plots, often with unpaved roads and irregular addressing. | «хороолол» also names apartment micro-districts («3, 4-р хороолол», «10-р хороолол»). These are TTS edge cases in NAV-007. | needs native review |
| Street / avenue / road | гудамж / өргөн чөлөө / зам | Road types in names, e.g. «Энхтайвны өргөн чөлөө». | | needs native review |
| Building / apartment number | байр / тоот | Parts of a UB address. | | needs native review |
| Intersection | уулзвар | A road junction, often a landmark in UB. | Valhalla: n/a (`<JUNCTION_NAME>` only). | needs native review |
| Traffic light | гэрлэн дохио | A signalised junction, used as a landmark. | | needs native review |
| Bridge / tunnel | гүүр / хонгил | Structures named in instructions when a road has no name. | Valhalla `empty_street_name_labels` «гүүр», «хонгил» → **match**. | needs native review |
| Pedestrian crossing / footway | явган хүний гарц / явган хүний зам | Unnamed pedestrian features read out in walking guidance. | Valhalla → **match** in wording. The Valhalla 3.9.0 «явган хүний зам» contains an invisible zero-width space (U+200B), which is reported under NAV-007. | needs native review |
| Unpaved road | шороон зам | A road without asphalt or concrete (OSM `surface=unpaved/dirt/gravel/...`). | See "Avoid unpaved roads". | needs native review |
| Petrol station | шатахуун түгээх станц (ШТС) | A fuel station POI. | «ШТС» is accepted in search. Never use it in voice. | needs native review |
| OSM attribution | «© OpenStreetMap contributors» | The ODbL credit line that must be visible on every map screen (CLAUDE.md rule 8). | **Do not translate.** The same string is used in `mn` and `en`. A Mongolian explanation may be added on the About screen, but it never replaces the credit line. | n/a (not translated) |
| OpenStreetMap | OpenStreetMap | The open map database the product is built on. | Brand name, not translated. | n/a (not translated) |

## 9. Technical terms (team-internal, not user-facing)
| English term | Approved Mongolian (UI/voice) | Definition (English) | Notes | Status |
|---|---|---|---|---|
| OSM extract | — | A regional subset of OpenStreetMap in `.osm.pbf` format. Dev uses the BBBike Ulaanbaatar extract. Production uses Geofabrik `mongolia-latest`. | | n/a (internal) |
| PMTiles | — | A single-file vector tile archive, read by clients with HTTP Range requests. Built by Planetiler using the Protomaps basemap schema. | | n/a (internal) |
| Range request | — | HTTP request with a `Range: bytes=a-b` header. The server answers `206 Partial Content`. PMTiles relies on it. | | n/a (internal) |
| CORS | — | Browser rule that lets a web page on one origin call an API on another. The gateway must send `Access-Control-Allow-*` headers. | | n/a (internal) |
| Costing | — | Valhalla's per-request travel profile (`auto`, `pedestrian`, `bicycle`, `taxi`, …) and its options (e.g. `exclude_unpaved`). | User-facing labels are under "Travel mode" and "Avoid unpaved roads". | n/a (internal) |
| OSRM-compatible output | — | Valhalla's `format=osrm` response shape (routes → legs → steps → maneuver) that Ferrostar and MapLibre Navigation consume. | | n/a (internal) |
| Manoeuvre | — | A single driving action at a point on the route (turn left, keep right, roundabout exit). | User-facing wording is in section 3. | n/a (internal) |
| Banner instruction | — | The on-screen text for the next manoeuvre (`bannerInstructions[].primary.text`), shown from `distanceAlongGeometry` onwards. | Wording is in sections 1 and 3. | n/a (internal) |
| Voice instruction | — | The spoken text for a manoeuvre (`voiceInstructions[].announcement`, optional SSML), triggered at `distanceAlongGeometry`. | Wording is in sections 1 and 3. The UI name is «дуут заавар». | n/a (internal) |
| mn-MN | — | Mongolian (Mongolia) locale code, used for Valhalla narrative and the app UI. | | n/a (internal) |
| Polyline6 | — | Encoded route geometry with 6-decimal precision. The default for Valhalla OSRM output. | | n/a (internal) |
| Off-route / reroute (SDK) | — | The navigation SDK detects the user has left the route beyond a distance/time threshold and requests a new route from the current position. | User-facing terms are "Off-route" and "Reroute / recalculating" in section 2. | n/a (internal) |
| ETA (technical) | — | Estimated time of arrival, derived from route `duration` (static speeds until traffic data exists). | User-facing term: «хүрэх цаг» (section 2). | n/a (internal) |
| Smoke test | — | A short automated check that each service answers correctly for known inputs. It doesn't test full behaviour. | | n/a (internal) |
| Gateway | — | The single HTTP entry point (Caddy or Nginx) that routes to tiles, routing and search and applies CORS. | | n/a (internal) |
| Golden announcement set | — | A snapshot of all `mn-MN` banner and voice strings for a fixed set of UB reference routes. Used to detect wording regressions (NAV-007). | | n/a (internal) |

## Change log
| Date | Issue | Change | Why |
|---|---|---|---|
| 2026-09-29 | — | Turned the glossary into a bilingual glossary that is binding for all agents. Added conventions C1 to C8 and about 90 navigation, search, map, settings, error and domain terms. Kept all existing entries: technical ones moved to section 9, place terms to section 8, ETA and off-route split into user-facing and technical rows. Compared with Valhalla 3.9.0 `mn-MN.json`. Marked every Mongolian term `needs native review`. | PO-approved language policy (glossary = one approved Mongolian term per concept). Review happens in NAV-007. |
