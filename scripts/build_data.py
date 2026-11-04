#!/usr/bin/env python3
"""Build src/nihongo/data.cljs from OpenJLPT + Hanabira + kana tables.

Sources (already fetched, see README for licences/attribution):
  data-raw/openjlpt/data/json/{vocab,kanji}/n{5,4,3,2,1}.json  (OpenJLPT, CC BY-SA 4.0)
  /tmp/hanabira-check/backend/express/json_data/grammar_ja_JLPT_N{5,4,3,2,1}_0001.json
    (Hanabira, CC in-house content; copied to data-raw/hanabira/ by this script's
     caller if present -- the script looks in both places and fails loudly if
     neither exists, so a build never silently ships the wrong grammar set.)

Output is one ClojureScript namespace, nihongo.data, with:
  kana    -- 214 hiragana/katakana items (basic, dakuten, yoon)
  kanji   -- 2,383 OpenJLPT kanji, each with a stable :id "kanji-<char>"
  vocab   -- 7,811 OpenJLPT words, :id "vocab-<openjlpt-id>", first example only
  grammar -- 828 Hanabira points, :id "grammar-n<level>-<nnn>" in file order
  counts  -- per-track/per-level counts, for the header and README

Trimming (to keep the bundled page reasonable):
  vocab: meanings capped at 3, first example only (ja + furigana + en)
  kanji: onyomi/kunyomi capped at 3, meanings at 4, words at 8, with the
         vocab reading looked up for each word when the word is in the vocab set
  grammar: short + long explanation, formation, first 2 examples (jp + en only;
           Hanabira examples carry no furigana field)
No stroke data is included, by request: JLPT does not test handwriting.
"""
import json, pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OPEN = ROOT / "data-raw" / "openjlpt" / "data" / "json"
HANABIRA_DIRS = [
    ROOT / "data-raw" / "hanabira",
    pathlib.Path("/tmp/hanabira-check/backend/express/json_data"),
]
LEVELS = ["N5", "N4", "N3", "N2", "N1"]

def cljs_str(s):
    return json.dumps(s, ensure_ascii=False)

def cljs_kw(k):
    return ":" + k

def emit_map(d):
    parts = []
    for k, v in d.items():
        parts.append(cljs_kw(k) + " " + emit(v))
    return "{" + " ".join(parts) + "}"

def emit(v):
    if v is None:
        return "nil"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, str):
        return cljs_str(v)
    if isinstance(v, (list, tuple)):
        return "[" + " ".join(emit(x) for x in v) + "]"
    if isinstance(v, dict):
        return emit_map(v)
    raise TypeError(repr(v))

# ---- Kana -----------------------------------------------------------------
# Each row is (kana, romaji). Basic rows are the 46 gojuuon (with ya/yu/yo and
# wa/wo gaps handled explicitly), then dakuten, then yoon (contracted sounds).
HIRAGANA_BASIC = [
    ("あ","a"),("い","i"),("う","u"),("え","e"),("お","o"),
    ("か","ka"),("き","ki"),("く","ku"),("け","ke"),("こ","ko"),
    ("さ","sa"),("し","shi"),("す","su"),("せ","se"),("そ","so"),
    ("た","ta"),("ち","chi"),("つ","tsu"),("て","te"),("と","to"),
    ("な","na"),("に","ni"),("ぬ","nu"),("ね","ne"),("の","no"),
    ("は","ha"),("ひ","hi"),("ふ","fu"),("へ","he"),("ほ","ho"),
    ("ま","ma"),("み","mi"),("む","mu"),("め","me"),("も","mo"),
    ("や","ya"),("ゆ","yu"),("よ","yo"),
    ("ら","ra"),("り","ri"),("る","ru"),("れ","re"),("ろ","ro"),
    ("わ","wa"),("を","wo"),("ん","n"),
]
HIRAGANA_DAKUTEN = [
    ("が","ga"),("ぎ","gi"),("ぐ","gu"),("げ","ge"),("ご","go"),
    ("ざ","za"),("じ","ji"),("ず","zu"),("ぜ","ze"),("ぞ","zo"),
    ("だ","da"),("ぢ","ji"),("づ","zu"),("で","de"),("ど","do"),
    ("ば","ba"),("び","bi"),("ぶ","bu"),("べ","be"),("ぼ","bo"),
    ("ぱ","pa"),("ぴ","pi"),("ぷ","pu"),("ぺ","pe"),("ぽ","po"),
]
HIRAGANA_YOON = [
    ("きゃ","kya"),("きゅ","kyu"),("きょ","kyo"),
    ("しゃ","sha"),("しゅ","shu"),("しょ","sho"),
    ("ちゃ","cha"),("ちゅ","chu"),("ちょ","cho"),
    ("にゃ","nya"),("にゅ","nyu"),("にょ","nyo"),
    ("ひゃ","hya"),("ひゅ","hyu"),("ひょ","hyo"),
    ("みゃ","mya"),("みゅ","myu"),("みょ","myo"),
    ("りゃ","rya"),("りゅ","ryu"),("りょ","ryo"),
    ("ぎゃ","gya"),("ぎゅ","gyu"),("ぎょ","gyo"),
    ("じゃ","ja"),("じゅ","ju"),("じょ","jo"),
    ("ぢゃ","ja"),("ぢゅ","ju"),("ぢょ","jo"),
    ("びゃ","bya"),("びゅ","byu"),("びょ","byo"),
    ("ぴゃ","pya"),("ぴゅ","pyu"),("ぴょ","pyo"),
]
KATAKANA_BASIC = [
    ("ア","a"),("イ","i"),("ウ","u"),("エ","e"),("オ","o"),
    ("カ","ka"),("キ","ki"),("ク","ku"),("ケ","ke"),("コ","ko"),
    ("サ","sa"),("シ","shi"),("ス","su"),("セ","se"),("ソ","so"),
    ("タ","ta"),("チ","chi"),("ツ","tsu"),("テ","te"),("ト","to"),
    ("ナ","na"),("ニ","ni"),("ヌ","nu"),("ネ","ne"),("ノ","no"),
    ("ハ","ha"),("ヒ","hi"),("フ","fu"),("ヘ","he"),("ホ","ho"),
    ("マ","ma"),("ミ","mi"),("ム","mu"),("メ","me"),("モ","mo"),
    ("ヤ","ya"),("ユ","yu"),("ヨ","yo"),
    ("ラ","ra"),("リ","ri"),("ル","ru"),("レ","re"),("ロ","ro"),
    ("ワ","wa"),("ヲ","wo"),("ン","n"),
]
KATAKANA_DAKUTEN = [
    ("ガ","ga"),("ギ","gi"),("グ","gu"),("ゲ","ge"),("ゴ","go"),
    ("ザ","za"),("ジ","ji"),("ズ","zu"),("ゼ","ze"),("ゾ","zo"),
    ("ダ","da"),("ヂ","ji"),("ヅ","zu"),("デ","de"),("ド","do"),
    ("バ","ba"),("ビ","bi"),("ブ","bu"),("ベ","be"),("ボ","bo"),
    ("パ","pa"),("ピ","pi"),("プ","pu"),("ペ","pe"),("ポ","po"),
]
KATAKANA_YOON = [
    ("キャ","kya"),("キュ","kyu"),("キョ","kyo"),
    ("シャ","sha"),("シュ","shu"),("ショ","sho"),
    ("チャ","cha"),("チュ","chu"),("チョ","cho"),
    ("ニャ","nya"),("ニュ","nyu"),("ニョ","nyo"),
    ("ヒャ","hya"),("ヒュ","hyu"),("ヒョ","hyo"),
    ("ミャ","mya"),("ミュ","myu"),("ミョ","myo"),
    ("リャ","rya"),("リュ","ryu"),("リョ","ryo"),
    ("ギャ","gya"),("ギュ","gyu"),("ギョ","gyo"),
    ("ジャ","ja"),("ジュ","ju"),("ジョ","jo"),
    ("ヂャ","ja"),("ヂュ","ju"),("ヂョ","jo"),
    ("ビャ","bya"),("ビュ","byu"),("ビョ","byo"),
    ("ピャ","pya"),("ピュ","pyu"),("ピョ","pyo"),
]

def kana_items():
    out = []
    for script, rows in [
        ("Hiragana", [("Basic", HIRAGANA_BASIC), ("Dakuten", HIRAGANA_DAKUTEN), ("Yoon", HIRAGANA_YOON)]),
        ("Katakana", [("Basic", KATAKANA_BASIC), ("Dakuten", KATAKANA_DAKUTEN), ("Yoon", KATAKANA_YOON)]),
    ]:
        for kind, pairs in rows:
            for ch, roma in pairs:
                out.append({
                    "id": f"kana-{script.lower()}-{ch}",
                    "track": "kana",  # emitted as a keyword below via a marker
                    "script": script, "kind": kind,
                    "display": ch, "reading": roma, "meaning": f"{script} {roma}",
                })
    return out

# The generic emitter treats "kana" as a string; fix up :track after the fact
# by emitting kana items with a dedicated path instead.
def emit_kana(items):
    vecs = []
    for it in items:
        vecs.append(
            "{:id %s :track :kana :script %s :kind %s :display %s :reading %s :meaning %s}"
            % (cljs_str(it["id"]), cljs_str(it["script"]), cljs_str(it["kind"]),
               cljs_str(it["display"]), cljs_str(it["reading"]), cljs_str(it["meaning"])))
    return "[" + " ".join(vecs) + "]"

# ---- Vocab / Kanji (OpenJLPT) ----------------------------------------------
def load_open(kind, lvl):
    return json.load(open(OPEN / kind / f"{lvl.lower()}.json"))

def clean_kun(k):
    # KANJIDIC2 notation: "た.べる" -> "たべる", "-び" stays "-び" (prefix/suffix)
    return k.replace(".", "")

def first_reading(k):
    on = k.get("onyomi") or []
    if on:
        return on[0]
    kun = [clean_kun(x) for x in (k.get("kunyomi") or [])]
    return kun[0] if kun else ""

def build():
    vocab, kanji, grammar = [], [], []
    vocab_reading = {}
    for lvl in LEVELS:
        for v in load_open("vocab", lvl):
            vocab_reading[v["word"]] = v["reading"]
    for lvl in LEVELS:
        for v in load_open("vocab", lvl):
            ex = None
            if v.get("examples"):
                e0 = v["examples"][0]
                ex = {"ja": e0["ja"], "furigana": e0.get("furigana"), "en": e0["en"]}
            vocab.append({
                "id": "vocab-" + v["id"], "track": ":vocab", "level": lvl,
                "display": v["word"], "reading": v["reading"], "romaji": v.get("romaji", ""),
                "meanings": list(v.get("meanings", []))[:3],
                "meaning": (v.get("meanings") or [""])[0],
                "example": ex,
            })
        for k in load_open("kanji", lvl):
            words = []
            for w in (k.get("words") or [])[:8]:
                words.append([w, vocab_reading.get(w)])
            kanji.append({
                "id": "kanji-" + k["character"], "track": ":kanji", "level": lvl,
                "display": k["character"], "reading": first_reading(k),
                "onyomi": list(k.get("onyomi") or [])[:3],
                "kunyomi": [clean_kun(x) for x in (k.get("kunyomi") or [])][:3],
                "meanings": list(k.get("meanings") or [])[:4],
                "meaning": (k.get("meanings") or [""])[0],
                "strokes": k.get("strokes"), "radical": k.get("radical"),
                "freq": k.get("freq"), "words": words,
            })
    # ---- Grammar (Hanabira) -------------------------------------------------
    han_dir = next((d for d in HANABIRA_DIRS if (d / "grammar_ja_JLPT_N5_0001.json").exists()), None)
    if han_dir is None:
        sys.exit("ERROR: Hanabira grammar JSON not found in data-raw/hanabira/ or /tmp/hanabira-check/")
    for lvl in LEVELS:
        data = json.load(open(han_dir / f"grammar_ja_JLPT_{lvl}_0001.json"))
        for i, g in enumerate(data, 1):
            title = g["title"]
            m = re.match(r"^(.*?)\s*\(([^)]*)\)\s*$", title)
            if m:
                pattern, roma = m.group(1), m.group(2)
            else:
                pattern, roma = title, ""
            examples = []
            for e in (g.get("examples") or [])[:2]:
                examples.append({"ja": e.get("jp", ""), "en": e.get("en", "")})
            grammar.append({
                "id": f"grammar-{lvl.lower()}-{i:03d}", "track": ":grammar", "level": lvl,
                "display": pattern, "reading": roma,
                "meaning": g.get("short_explanation", ""),
                "short": g.get("short_explanation", ""),
                "long": g.get("long_explanation", ""),
                "formation": g.get("formation", ""),
                "examples": examples,
            })
    return kana_items(), kanji, vocab, grammar

def main():
    kana, kanji, vocab, grammar = build()
    counts = {
        "kana": len(kana),
        "kanji": {lvl: sum(1 for k in kanji if k["level"] == lvl) for lvl in LEVELS},
        "vocab": {lvl: sum(1 for v in vocab if v["level"] == lvl) for lvl in LEVELS},
        "grammar": {lvl: sum(1 for g in grammar if g["level"] == lvl) for lvl in LEVELS},
    }
    print("kana", len(kana), "kanji", len(kanji), "vocab", len(vocab), "grammar", len(grammar))
    print("counts", counts)
    # :track values were stored as ":vocab" strings so emit() keeps them as
    # keywords via a small hack: emit normally, then the :track string
    # ":vocab" would be quoted -- instead, build the file with explicit
    # keyword handling: replace '"track":' patterns is fragile, so instead
    # we post-process: for kanji/vocab/grammar, their dicts contain
    # "track": ":xxx" and emit() produces :track ":xxx"; fix by string replace.
    def fix_track(text, track):
        return text.replace(f':track "{track}"', f':track {track}')
    out = []
    out.append("(ns nihongo.data)")
    out.append("")
    out.append(";; Generated by scripts/build_data.py -- do not edit by hand.")
    out.append(";; Vocab + kanji: OpenJLPT (CC BY-SA 4.0). Grammar: Hanabira (CC content).")
    out.append(";; No stroke data: JLPT does not test handwriting.")
    out.append("")
    out.append("(def kana " + emit_kana(kana) + ")")
    out.append("")
    out.append("(def kanji " + fix_track(emit(kanji), ":kanji") + ")")
    out.append("")
    out.append("(def vocab " + fix_track(emit(vocab), ":vocab") + ")")
    out.append("")
    out.append("(def grammar " + fix_track(emit(grammar), ":grammar") + ")")
    out.append("")
    out.append("(def counts " + emit({
        "kana": len(kana),
        "kanji": counts["kanji"], "vocab": counts["vocab"], "grammar": counts["grammar"],
        "total": len(kana) + len(kanji) + len(vocab) + len(grammar),
    }) + ")")
    dest = ROOT / "src" / "nihongo" / "data.cljs"
    dest.write_text("\n".join(out) + "\n", encoding="utf-8")
    print("wrote", dest, dest.stat().st_size, "bytes")

if __name__ == "__main__":
    main()
