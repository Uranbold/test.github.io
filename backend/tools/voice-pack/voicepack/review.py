"""Review files for the PO's per-segment listen (NAV-016 AC 17, D230; task B8, B11).

review/index.html   offline page per docs/design/screens/NAV-016-voice-pack-review.md (labels in English; the
                    labels sit in LABELS so a Mongolian column is a data change once the BA adds glossary rows)
review/review.tsv   key, take, spoken_mn, result (empty), note
review/prompts/     rendered prompts; review/prompts.tsv lists them with durationMs
"""

from __future__ import annotations

import html
import json
from pathlib import Path

from .render import Prompt

# label id -> {"en": text, "mn": text or None}. No glossary term exists for any of them yet (screen spec "Copy").
LABELS = {
    "title": {"en": "Voice pack review", "mn": None},
    "play": {"en": "Play", "mn": None},
    "stop": {"en": "Stop", "mn": None},
    "playFromHere": {"en": "Play from here", "mn": None},
    "colText": {"en": "Spoken text", "mn": None},
    "colTake": {"en": "Take", "mn": None},
    "colDuration": {"en": "Duration", "mn": None},
    "colResult": {"en": "Result", "mn": None},
    "colNote": {"en": "Note", "mn": None},
    "progress": {"en": "Checked {n} / {total} · bad {b}", "mn": None},
    "filterAll": {"en": "All", "mn": None},
    "filterUnchecked": {"en": "Unchecked", "mn": None},
    "filterBad": {"en": "Bad", "mn": None},
    "filterNonFinal": {"en": "Non-final takes", "mn": None},
    "download": {"en": "Download review.tsv", "mn": None},
    "samplePrompts": {"en": "Sample prompts", "mn": None},
    "longestSingle": {"en": "Longest single", "mn": None},
    "longestChained": {"en": "Longest chained", "mn": None},
    "ready": {"en": "All segments checked, none bad", "mn": None},
    "storageWarning": {"en": "Results are not saved in this browser. Download review.tsv before closing.", "mn": None},
    "stubBanner": {"en": "Stub pack: synthetic tones, not for listening.", "mn": None},
    "codecBanner": {"en": "This browser cannot play the clips. Use Chrome, Edge or Safari.", "mn": None},
    "gpsNote": {"en": "GPS prompts have no clip in this pack yet.", "mn": None},
    "clipError": {"en": "This clip cannot be played (file missing or not decodable).", "mn": None},
    "show": {"en": "Show:", "mn": None},
    "jumpPrompts": {"en": "Sample prompts", "mn": None},
    "measured": {"en": "Measured (pre-screen)", "mn": None},
    "coverage": {"en": "Coverage", "mn": None},
    "longestList": {"en": "Longest list", "mn": None},
    "display": {"en": "display", "mn": None},
    "spoken": {"en": "spoken", "mn": None},
}

GROUPS = [
    ("Distances", lambda k: k.startswith("dist.") or k.startswith("cont.")),
    ("Turns", lambda k: k.startswith("instr.turn.")),
    ("U-turn and straight", lambda k: k in ("instr.uturn", "instr.straight")),
    ("Keep", lambda k: k.startswith("instr.keep.")),
    ("Merge", lambda k: k == "instr.merge" or k.startswith("instr.merge.")),
    ("Ramps", lambda k: k.startswith("instr.onRamp") or k.startswith("instr.offRamp")),
    ("Roundabout", lambda k: k.startswith("instr.roundabout.")),
    ("Depart", lambda k: k.startswith("instr.depart.")),
    ("Arrival", lambda k: k == "instr.arrive" or k.startswith("instr.arrive.")),
    ("Tails", lambda k: k.startswith("tail.")),
    ("Join", lambda k: k.startswith("join.")),
    ("Fixed", lambda k: k.startswith("fixed.")),
]


def group_of(key: str) -> str:
    for name, pred in GROUPS:
        if pred(key):
            return name
    return "Other"


def label(lid: str) -> str:
    v = LABELS[lid]
    return v.get("mn") or v["en"]


def review_tsv(manifest: dict) -> bytes:
    lines = ["key\ttake\tspoken_mn\tresult\tnote"]
    for e in manifest["entries"]:
        lines.append(f"{e['key']}\t{e['take']}\t{e['spokenText']}\t\t")
    return ("\n".join(lines) + "\n").encode("utf-8")


def _page_data(manifest: dict, prompt_rows: list[dict]) -> dict:
    entries = [
        {
            "key": e["key"],
            "take": e["take"],
            "spoken": e["spokenText"],
            "src": "../pack/" + e["file"],
            "durationMs": e["durationMs"],
            "group": group_of(e["key"]),
        }
        for e in manifest["entries"]
    ]
    prompts = []
    for r in prompt_rows:
        if not r.get("file"):
            continue
        segs = [] if r["segments"] == "-" else [dict(zip(("key", "take"), t.split(":", 1))) for t in r["segments"].split(" ")]
        prompts.append({
            "id": r["id"],
            "kind": r.get("kind", ""),
            "mark": r.get("mark", ""),
            "durationMs": r["durationMs"],
            "spoken": r.get("spoken", ""),
            "display": r.get("display", ""),
            "segments": segs,
            "src": r["file"],
        })
    keys = {e["key"] for e in manifest["entries"]}
    return {
        "meta": {
            "voiceId": manifest["voiceId"],
            "packVersion": manifest["packVersion"],
            "vendor": manifest["source"]["vendor"],
            "apiVersion": manifest["source"]["apiVersion"],
            "takeMethod": manifest["takeMethod"],
            "joinPauseMs": manifest["joinPauseMs"],
            "thenPauseMs": manifest["thenPauseMs"],
            "glossaryRevision": manifest["glossaryRevision"],
            "inventorySha256": manifest["inventorySha256"],
            "count": len(entries),
            "gpsMissing": not ({"fixed.gpsLost", "fixed.gpsRestored"} <= keys),
        },
        "groups": [g for g, _ in GROUPS] + ["Other"],
        "labels": {k: label(k) for k in LABELS},
        "entries": entries,
        "prompts": prompts,
    }


def index_html(manifest: dict, prompt_rows: list[dict]) -> bytes:
    data = json.dumps(_page_data(manifest, prompt_rows), ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    data = data.replace("</", "<\\/").replace("<!--", "<\\!--")
    page = _TEMPLATE.replace("@@TITLE@@", html.escape(label("title"))).replace("@@DATA@@", data)
    return page.encode("utf-8")


def write_review(review_dir: Path, manifest: dict, prompt_rows: list[dict]) -> None:
    review_dir.mkdir(parents=True, exist_ok=True)
    (review_dir / "review.tsv").write_bytes(review_tsv(manifest))
    (review_dir / "index.html").write_bytes(index_html(manifest, prompt_rows))


def coverage_prompts(path: Path) -> list[Prompt]:
    from .render import parse_prompt_list

    ps = parse_prompt_list(path.read_text(encoding="utf-8"), str(path.name))
    for p in ps:
        p.extra = {"kind": "coverage"}
    return ps


MEASURED_PROMPTS = [
    # navigation-ux §4.7: the two whole-sentence prompts measured in the F4 pre-screen (D226)
    Prompt("measured-single", ["dist.km.1_5:final", "instr.turn.slightRight:final"], {"kind": "measured"}),
    Prompt("measured-chained", ["dist.m.200:final", "instr.roundabout.exit.2:nonFinal", "join.then:final",
                                "instr.turn.slightRight:final"], {"kind": "measured"}),
]
SINGLE_LIMIT_MS = 5000
CHAINED_LIMIT_MS = 8000


def superset_longest(manifest: dict, top: int = 10) -> tuple[list[Prompt], list[Prompt]]:
    """The generator's own superset of prompt shapes (ADR-0010 §2) ranked by promptMs (task B8, B11):
    single  = [prefix] instr | dist.m.N tail.approach | cont.km.N tail.continue | fixed.*
    chained = [prefix] instr(nonFinal) join.then instr
    It ignores the schedule's constraints, so it is an upper bound of the real domain (Android M4 is authoritative)."""
    ents = {(e["key"], e["take"]): e for e in manifest["entries"]}
    dur = {k: e["durationMs"] for k, e in ents.items()}
    jp, tp = manifest["joinPauseMs"], manifest["thenPauseMs"]
    prefixes = [k for (k, t) in ents if t == "final" and k.startswith("dist.")]
    instr_f = [k for (k, t) in ents if t == "final" and k.startswith("instr.")]
    instr_n = [k for (k, t) in ents if t == "nonFinal" and k.startswith("instr.")]
    singles: list[tuple[int, list[str]]] = []
    for i in instr_f:
        singles.append((dur[(i, "final")], [f"{i}:final"]))
        for p in prefixes:
            singles.append((dur[(p, "final")] + jp + dur[(i, "final")], [f"{p}:final", f"{i}:final"]))
    if ("tail.approach", "final") in ents:
        for p in prefixes:
            if p.startswith("dist.m."):
                singles.append((dur[(p, "final")] + jp + dur[("tail.approach", "final")], [f"{p}:final", "tail.approach:final"]))
    if ("tail.continue", "final") in ents:
        for (k, t) in ents:
            if t == "final" and k.startswith("cont.km."):
                singles.append((dur[(k, "final")] + jp + dur[("tail.continue", "final")], [f"{k}:final", "tail.continue:final"]))
    for (k, t) in ents:
        if t == "final" and k.startswith("fixed."):
            singles.append((dur[(k, "final")], [f"{k}:final"]))
    chained: list[tuple[int, list[str]]] = []
    if ("join.then", "final") in ents:
        th = dur[("join.then", "final")]
        best_second = sorted(instr_f, key=lambda i: (-dur[(i, "final")], i))
        for first in instr_n:
            for p in [None] + prefixes:
                head = dur[(first, "nonFinal")] + (dur[(p, "final")] + jp if p else 0)
                toks = ([f"{p}:final"] if p else []) + [f"{first}:nonFinal", "join.then:final"]
                for second in best_second[: top + 1]:
                    chained.append((head + tp + th + tp + dur[(second, "final")], toks + [f"{second}:final"]))
    singles.sort(key=lambda x: (-x[0], " ".join(x[1])))
    chained.sort(key=lambda x: (-x[0], " ".join(x[1])))
    s = [Prompt(f"superset-longest-single-{i + 1:02d}", toks, {"kind": "superset-longest-single"}) for i, (_, toks) in enumerate(singles[:top])]
    c = [Prompt(f"superset-longest-chained-{i + 1:02d}", toks, {"kind": "superset-longest-chained"}) for i, (_, toks) in enumerate(chained[:top])]
    return s, c


_TEMPLATE = r"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>@@TITLE@@</title>
<style>
:root{--surface:#FFFFFF;--surface-container:#F1F3F4;--on-surface:#1F1F1F;--on-surface-variant:#444746;--outline:#747775;
--outline-variant:#C4C7C5;--primary:#0B57D0;--primary-container:#D3E3FD;--error:#B3261E;--error-container:#F9DEDC;
--on-error-container:#410E0B;--focus:#0B57D0}
*{box-sizing:border-box}
body{margin:0;background:var(--surface);color:var(--on-surface);font:16px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans",Arial,sans-serif}
.wrap{max-width:1200px;margin:0 auto;padding:0 16px 48px}
header{padding:16px 0 8px}
h1{font-size:22px;margin:0 0 6px}
.meta{color:var(--on-surface-variant);font-size:14px;margin:2px 0}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.banner{padding:10px 12px;border-radius:8px;margin:8px 0;background:var(--error-container);color:var(--on-error-container)}
.toolbar{position:sticky;top:0;z-index:5;background:var(--surface);border-bottom:1px solid var(--outline-variant);
display:flex;flex-wrap:wrap;gap:8px;align-items:center;padding:8px 0}
.toolbar .sep{width:1px;height:28px;background:var(--outline-variant)}
button,.btn{min-height:44px;min-width:44px;padding:0 14px;border:1px solid var(--outline);border-radius:8px;background:var(--surface);
color:var(--on-surface);font:inherit;cursor:pointer}
button[aria-pressed=true]{background:var(--primary-container);border-color:var(--primary)}
button:disabled{opacity:.38;cursor:not-allowed}
:focus-visible{outline:3px solid var(--focus);outline-offset:2px}
a{color:var(--primary)}
#status{font-variant-numeric:tabular-nums}
#ready{color:var(--primary);font-weight:600}
h2{font-size:18px;margin:24px 0 8px}
.gnote{color:var(--on-surface-variant);font-size:14px;margin:-4px 0 8px}
.row{display:grid;grid-template-columns:48px minmax(0,1fr) 92px 64px auto minmax(120px,220px);gap:8px;align-items:center;
padding:6px 8px;border-bottom:1px solid var(--outline-variant);border-left:4px solid transparent}
.row:focus{outline:none;box-shadow:inset 0 0 0 2px var(--focus)}
.row.playing{border-left-color:var(--primary);background:var(--primary-container)}
.row .play{width:44px;padding:0}
.txt{font-size:18px}
.key{font-size:13px;color:var(--on-surface-variant)}
.take,.dur{font-size:14px;color:var(--on-surface-variant)}
.seg{display:inline-flex;border:1px solid var(--outline);border-radius:8px;overflow:hidden}
.seg label{display:inline-flex;align-items:center;justify-content:center;min-width:52px;min-height:44px;cursor:pointer;font-size:14px}
.seg input{position:absolute;opacity:0;width:1px;height:1px}
.seg input:checked+span{font-weight:700}
.seg label:has(input:checked){background:var(--primary-container)}
.seg label.bad:has(input:checked){background:var(--error-container)}
.seg input:focus-visible+span{outline:3px solid var(--focus);outline-offset:2px}
.seg input:disabled+span{opacity:.38}
.note{min-height:44px;width:100%;padding:0 8px;border:1px solid var(--outline);border-radius:8px;font:inherit}
.err{grid-column:2/-1;color:var(--error);font-size:14px}
.hidden{display:none!important}
.card{border:1px solid var(--outline-variant);border-left:4px solid transparent;border-radius:8px;padding:10px 12px;margin:8px 0;
display:grid;grid-template-columns:48px minmax(0,1fr) auto;gap:8px;align-items:start}
.card.playing{border-left-color:var(--primary);background:var(--primary-container)}
.card .badge{font-size:12px;font-weight:700;letter-spacing:.04em;text-transform:uppercase;color:var(--primary)}
.card .segs{font-size:13px;color:var(--on-surface-variant)}
.card .side{display:grid;gap:6px;justify-items:end}
@media (max-width:719px){
.row{grid-template-columns:48px minmax(0,1fr);}
.row .take,.row .dur,.row .seg,.row .note{grid-column:2}
.card{grid-template-columns:48px minmax(0,1fr)}
.card .side{grid-column:2;justify-items:start}
}
</style>
</head>
<body>
<div class="wrap">
<header>
<h1 id="h-title"></h1>
<p class="meta" id="h-1"></p>
<p class="meta" id="h-2"></p>
<p class="meta mono" id="h-3"></p>
</header>
<div id="banners"></div>
<div class="toolbar" role="toolbar" aria-label="Review tools">
<span id="status" aria-live="polite"></span>
<span id="ready" class="hidden"></span>
<a href="#" id="bad-link" class="hidden"></a>
<span class="sep" aria-hidden="true"></span>
<button type="button" id="btn-from"></button>
<button type="button" id="btn-stop"></button>
<span class="sep" aria-hidden="true"></span>
<span id="lbl-show"></span>
<span id="filters" role="group"></span>
<span class="sep" aria-hidden="true"></span>
<button type="button" id="btn-dl"></button>
<a href="#prompts" id="jump"></a>
<span id="storage" class="hidden"></span>
</div>
<main id="list"></main>
<section id="prompts" aria-labelledby="prompts-h"><h2 id="prompts-h"></h2><div id="cards"></div></section>
</div>
<audio id="player" preload="none"></audio>
<script type="application/json" id="data">@@DATA@@</script>
<script>
(function(){
"use strict";
var D=JSON.parse(document.getElementById("data").textContent);
var L=D.labels, M=D.meta;
var $=function(id){return document.getElementById(id);};
function el(tag,attrs,kids){var e=document.createElement(tag);if(attrs)for(var k in attrs){if(k==="text")e.textContent=attrs[k];else if(k==="cls")e.className=attrs[k];else e.setAttribute(k,attrs[k]);}
(kids||[]).forEach(function(c){if(c)e.appendChild(c);});return e;}
function fmtS(ms){return (ms/1000).toFixed(2)+" s";}
var STUB=M.vendor==="stub";
var SKEY="navmn-voice-review:"+M.voiceId+":"+M.packVersion+":"+M.inventorySha256;
var storageOk=true, state={marks:{},prompts:{}}, dirty=false;
try{var t="navmn-probe";localStorage.setItem(t,t);localStorage.removeItem(t);var s=localStorage.getItem(SKEY);if(s)state=JSON.parse(s);
if(!state.marks)state.marks={};if(!state.prompts)state.prompts={};}catch(e){storageOk=false;}
function save(){dirty=true;if(!storageOk)return;try{localStorage.setItem(SKEY,JSON.stringify(state));}catch(e){storageOk=false;showStorage();}}
function showStorage(){var s=$("storage");s.textContent=L.storageWarning;s.classList.remove("hidden");}
if(!storageOk)showStorage();
window.addEventListener("beforeunload",function(ev){if(!storageOk&&dirty){ev.preventDefault();ev.returnValue="";}});
/* header */
$("h-title").textContent=L.title;document.title=L.title;
$("h-1").textContent="voiceId "+M.voiceId+" · packVersion "+M.packVersion+" · source "+M.vendor+" "+M.apiVersion;
$("h-2").textContent=M.count+" entries · takeMethod "+M.takeMethod+" · join "+M.joinPauseMs+" ms · then "+M.thenPauseMs+" ms";
$("h-3").textContent="glossaryRevision "+M.glossaryRevision.slice(0,12)+"… · inventorySha256 "+M.inventorySha256.slice(0,12)+"…";
var player=$("player");
var canPlay=!!(player.canPlayType&&player.canPlayType("audio/aac"));
if(STUB)$("banners").appendChild(el("div",{cls:"banner",role:"note",text:L.stubBanner}));
if(!canPlay)$("banners").appendChild(el("div",{cls:"banner",role:"alert",text:L.codecBanner}));
$("btn-from").textContent=L.playFromHere;$("btn-stop").textContent=L.stop;$("btn-dl").textContent=L.download;
$("lbl-show").textContent=L.show;$("jump").textContent="↓ "+L.jumpPrompts;$("prompts-h").textContent=L.samplePrompts;
/* rows */
var rows=[];
function tokenOf(e){return e.key+":"+e.take;}
function resultSeg(name,current,onChange){
var seg=el("span",{cls:"seg",role:"radiogroup","aria-label":name});
["ok","bad"].forEach(function(v){var id="r-"+name.replace(/[^A-Za-z0-9]/g,"_")+"-"+v;
var inp=el("input",{type:"radio",name:"res-"+name,value:v,id:id});if(current===v)inp.checked=true;if(STUB)inp.disabled=true;
inp.addEventListener("change",function(){onChange(v);});
seg.appendChild(el("label",{cls:v,"for":id},[inp,el("span",{text:v})]));});
return seg;}
var list=$("list");
D.groups.forEach(function(g){
var es=D.entries.filter(function(e){return e.group===g;});if(!es.length)return;
var sec=el("section",{"aria-label":g});
var hd=el("h2",{text:g+" ("+es.length+")"});sec.appendChild(hd);
if(g==="Fixed"&&M.gpsMissing)sec.appendChild(el("p",{cls:"gnote",text:L.gpsNote}));
es.forEach(function(e){
var tok=tokenOf(e), mk=state.marks[tok]||{};
var row=el("div",{cls:"row",tabindex:"0","data-token":tok});
var btn=el("button",{type:"button",cls:"play","aria-label":L.play+" "+e.key+" "+e.take,text:"▶"});if(!canPlay)btn.disabled=true;
btn.addEventListener("click",function(){stopRun();play(r);});
var txt=el("div",{},[el("div",{cls:"txt",lang:"mn",text:e.spoken}),el("div",{cls:"key mono",text:e.key})]);
var note=el("input",{type:"text",cls:"note","aria-label":L.colNote+" "+e.key+" "+e.take,placeholder:L.colNote+"…"});
note.value=mk.note||"";
note.addEventListener("input",function(){var m=state.marks[tok]||(state.marks[tok]={});m.note=note.value;save();});
note.addEventListener("keydown",function(ev){if(ev.key==="Escape"){ev.preventDefault();ev.stopPropagation();row.focus();}});
var seg=resultSeg(e.key+" "+e.take,mk.result,function(v){setResult(r,v);});
var r={e:e,tok:tok,row:row,btn:btn,note:note,seg:seg,section:sec,broken:false};
row.appendChild(btn);row.appendChild(txt);row.appendChild(el("span",{cls:"take mono",text:e.take}));
row.appendChild(el("span",{cls:"dur",text:fmtS(e.durationMs)}));row.appendChild(seg);row.appendChild(note);
sec.appendChild(row);rows.push(r);
row.addEventListener("keydown",function(ev){onRowKey(ev,r);});
});
list.appendChild(sec);});
function setResult(r,v){if(STUB)return;var m=state.marks[r.tok]||(state.marks[r.tok]={});m.result=v;
r.seg.querySelectorAll("input").forEach(function(i){i.checked=(i.value===v);});save();update();applyFilter();}
/* filters */
var filter="all";
var FILTERS=[["all",L.filterAll],["unchecked",L.filterUnchecked],["bad",L.filterBad],["nonFinal",L.filterNonFinal]];
FILTERS.forEach(function(f){var b=el("button",{type:"button","aria-pressed":String(f[0]==="all"),"data-f":f[0],text:f[1]});
b.addEventListener("click",function(){setFilter(f[0]);});$("filters").appendChild(b);});
function setFilter(f){filter=f;$("filters").querySelectorAll("button").forEach(function(b){b.setAttribute("aria-pressed",String(b.getAttribute("data-f")===f));});applyFilter();}
function visible(r){var m=state.marks[r.tok]||{};
if(filter==="unchecked")return !m.result;if(filter==="bad")return m.result==="bad";if(filter==="nonFinal")return r.e.take==="nonFinal";return true;}
function applyFilter(){rows.forEach(function(r){r.row.classList.toggle("hidden",!visible(r));});}
/* progress */
function update(){var n=0,b=0;rows.forEach(function(r){var m=state.marks[r.tok];if(m&&m.result){n++;if(m.result==="bad")b++;}});
$("status").textContent=L.progress.replace("{n}",n).replace("{total}",rows.length).replace("{b}",b);
var done=n===rows.length&&b===0;$("ready").textContent=L.ready;$("ready").classList.toggle("hidden",!done);
var bl=$("bad-link");bl.textContent=L.filterBad+" ("+b+")";bl.classList.toggle("hidden",b===0);}
$("bad-link").addEventListener("click",function(ev){ev.preventDefault();setFilter("bad");});
update();
/* playback */
var current=null, run=null;
function highlight(target){document.querySelectorAll(".playing").forEach(function(x){x.classList.remove("playing");});if(target)target.classList.add("playing");}
function play(r,onEnd){if(!canPlay||r.broken){if(onEnd)onEnd();return;}
current=r;highlight(r.row||r.card);player.onended=function(){highlight(null);current=null;if(onEnd)onEnd();};
player.onerror=function(){markBroken(r);highlight(null);current=null;if(onEnd)onEnd();};
player.src=r.e?r.e.src:r.p.src;var pr=player.play();if(pr&&pr.catch)pr.catch(function(){});}
function markBroken(r){if(r.broken)return;r.broken=true;r.btn.disabled=true;var host=r.row||r.card;host.appendChild(el("div",{cls:"err",role:"alert",text:L.clipError}));}
function stopPlayback(){player.pause();player.removeAttribute("src");try{player.load();}catch(e){}highlight(null);current=null;}
function stopRun(){if(run){clearTimeout(run.timer);run=null;}stopPlayback();}
function playFrom(start){stopRun();var vis=rows.filter(function(r){return !r.row.classList.contains("hidden");});
var i=Math.max(0,vis.indexOf(start));run={timer:null};
(function next(){if(!run)return;if(i>=vis.length){run=null;return;}var r=vis[i++];r.row.scrollIntoView({block:"nearest"});
play(r,function(){if(run)run.timer=setTimeout(next,700);});})();}
$("btn-from").addEventListener("click",function(){var f=document.activeElement&&document.activeElement.closest?document.activeElement.closest(".row"):null;
var r=rows.find(function(x){return x.row===f;})||rows.find(function(x){return !x.row.classList.contains("hidden");});if(r)playFrom(r);});
$("btn-stop").addEventListener("click",stopRun);
document.addEventListener("keydown",function(ev){if(ev.key==="Escape"&&!(ev.target&&ev.target.tagName==="INPUT"&&ev.target.type==="text"))stopRun();});
function moveFocus(r,dir){var vis=rows.filter(function(x){return !x.row.classList.contains("hidden");});var i=vis.indexOf(r)+dir;if(i>=0&&i<vis.length)vis[i].row.focus();}
function onRowKey(ev,r){if(ev.target!==r.row)return;
if(ev.key===" "||ev.key==="Spacebar"){ev.preventDefault();stopRun();play(r);}
else if(ev.key==="ArrowDown"){ev.preventDefault();moveFocus(r,1);}
else if(ev.key==="ArrowUp"){ev.preventDefault();moveFocus(r,-1);}
else if(ev.key==="1"){ev.preventDefault();setResult(r,"ok");}
else if(ev.key==="2"){ev.preventDefault();setResult(r,"bad");}
else if(ev.key==="Enter"){ev.preventDefault();r.note.focus();}
else if(ev.key==="Escape"){stopRun();}}
/* download */
function clean(s){return String(s||"").replace(/[\t\r\n]+/g," ");}
$("btn-dl").addEventListener("click",function(){var out=["key\ttake\tspoken_mn\tresult\tnote"];
D.entries.forEach(function(e){var m=state.marks[tokenOf(e)]||{};out.push([e.key,e.take,e.spoken,m.result||"",clean(m.note)].join("\t"));});
var blob=new Blob([out.join("\n")+"\n"],{type:"text/tab-separated-values;charset=utf-8"});
var a=el("a",{href:URL.createObjectURL(blob),download:"review.tsv"});document.body.appendChild(a);a.click();
setTimeout(function(){URL.revokeObjectURL(a.href);a.remove();},1000);dirty=false;});
/* sample prompts */
var KIND={"measured":L.measured,"coverage":L.coverage};
D.prompts.forEach(function(p){var pm=state.prompts[p.id]||{};
var badge=p.mark==="single"?L.longestSingle:p.mark==="chained"?L.longestChained:(KIND[p.kind]||L.longestList);
var card=el("div",{cls:"card",tabindex:"0"});
var btn=el("button",{type:"button",cls:"play","aria-label":L.play+" "+p.id,text:"▶"});if(!canPlay)btn.disabled=true;
var body=el("div",{},[el("div",{cls:"badge",text:fmtS(p.durationMs)+"  "+badge}),
p.display?el("div",{},[el("span",{cls:"key",text:L.display+" "}),el("span",{lang:"mn",text:"«"+p.display+"»"})]):null,
el("div",{},[el("span",{cls:"key",text:L.spoken+" "}),el("span",{cls:"txt",lang:"mn",text:"«"+p.spoken+"»"})]),
el("div",{cls:"segs mono",text:p.segments.map(function(s){return s.take==="nonFinal"?s.key+" (nonFinal)":s.key;}).join(" · ")}),
el("div",{cls:"key mono",text:p.id})]);
var note=el("input",{type:"text",cls:"note","aria-label":L.colNote+" "+p.id,placeholder:L.colNote+"…"});note.value=pm.note||"";
note.addEventListener("input",function(){var m=state.prompts[p.id]||(state.prompts[p.id]={});m.note=note.value;save();});
var seg=resultSeg(p.id,pm.result,function(v){if(STUB)return;var m=state.prompts[p.id]||(state.prompts[p.id]={});m.result=v;save();});
var r={p:p,card:card,btn:btn,broken:false};
btn.addEventListener("click",function(){stopRun();play(r);});
card.appendChild(btn);card.appendChild(body);card.appendChild(el("div",{cls:"side"},[seg,note]));$("cards").appendChild(card);});
var first=rows.find(function(r){return !r.row.classList.contains("hidden");});if(first)first.row.focus({preventScroll:true});
applyFilter();
})();
</script>
</body>
</html>
"""
