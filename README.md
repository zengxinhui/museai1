# Japanese Learner — learn Japanese for JLPT N5 to N1

A single-page app written in **ClojureScript + Reagent only** (no other
front-end framework or library). The compiled page is `public/index.html`
plus `public/js/main.js` (built, not tracked — see "Build" below).
There is no server and no network call at run time: all data is
bundled, and all user data stays in the browser.

## What it teaches, and how

Four tracks, taught **at most 5 at a time** per track and level.
The current batch is the first 5 items in that track/level that are
not yet Learned. When all 5 are Learned, the next 5 unlock
automatically.

- **Kana** — 214 items: hiragana and katakana, basic, dakuten and
  yōon (contracted sounds). Kana has no JLPT level.
- **Kanji** — 2,383 kanji by JLPT level (N5 84, N4 169, N3 387,
  N2 398, N1 1,345), with on'yomi, kun'yomi, meanings, stroke count,
  radical, frequency and example words.
- **Vocabulary** — 7,811 words by JLPT level (N5 674, N4 630,
  N3 1,659, N2 1,778, N1 3,070), with reading, romaji, meanings and
  an example sentence.
- **Grammar** — 828 points by JLPT level (N5 136, N4 124, N3 132,
  N2 191, N1 245), with meaning, formation, a full explanation and
  example sentences.

Total: **11,236 items**.

Each item has one of three states, per account:

- **New** — never studied.
- **Learning** — studied, but has not yet passed its quiz.
- **Learned** — passed its quiz, now on spaced review.

Flow:

1. **Learn** — pick a track and a level, study the batch of 5:
   readings, meanings, explanations and example sentences.
2. **Quiz** — for items in the current batch that are in Learning.
   Kana: pick the romaji. Kanji/vocabulary: pick the reading **and**
   the meaning. Grammar: pick the meaning. Passing moves an item to
   Learned.
3. **Review** — Learned items come back on a spaced schedule:
   1, 2, 4, 8, 16, then 32 days after the previous pass (32 days is
   the upper limit). Passing goes up a step; failing resets the
   interval to 1 day, with the item staying Learned and due again
   tomorrow.
4. **Browse** — a searchable reference list for the selected track
   and level (first 200 shown; search to narrow it down). Browsing
   does not change the teaching order.
5. **Accounts** — switch, delete, download an EDN backup, or
   restore from a backup file.

## Furigana

A **Furigana: On/Off** toggle sits in the header. When on, example
sentences that carry furigana show small readings above the kanji,
using native HTML `<ruby>` — no extra library.

- Vocabulary and grammar example sentences from OpenJLPT carry
  furigana in `{kanji|reading}` notation (vocab examples 10,517 of
  13,718; grammar examples 1,510 of 1,526 in the source data), and
  the app renders that notation as ruby.
- Hanabira grammar examples have **no** furigana field (only
  Japanese, romaji and English), so grammar examples in this app
  show plain Japanese. That is a data limitation, not a rendering
  one: if a furigana source for those sentences is added later,
  the same ruby renderer will pick it up.
- **Furigana is always hidden in quizzes**, otherwise a reading
  question would give itself away.

## What this app deliberately does not do

- **No stroke order or handwriting practice.** JLPT does not test
  handwriting, so kanji are taught as character + readings +
  meanings only.
- **No mock exams, listening or reading passages yet.** Those are
  a possible later layer (see "Data sources" for why a mock bank
  needs original authorship, not a copied exam).
- **No pitch accent or audio.** Not in the source datasets used
  here.

## Accounts and localStorage

All user account data is saved to **this browser's localStorage**,
under the single key `nihongo-learner-v1`, as one EDN string.
Nothing is sent anywhere. Accounts are just names (no server, so
no real password would be meaningful); each account has its own
progress, and you can switch, delete, or download a backup (EDN)
from the Accounts tab. Clearing this site's browser data deletes
the accounts.

Stored shape (per account, per item):

```clojure
{:state "learning" :reviews 0 :level 0
 :learned-at 0 :next-review 0 :last-seen 0}
```

Absence of a progress entry means **New**.

## Data sources and formats

### A note on JLPT levels

JLPT has not published official vocabulary, kanji or grammar lists
since the 2010 revision. Every level tag in every source traces
back to Jonathan Waller's tanos.co.uk lists (CC BY), as used by
Jisho.org. Levels here are therefore a community-standard,
well-informed approximation — not a guarantee of exam content,
and not an official list.

### 1. Vocabulary and kanji — OpenJLPT

Source: <https://github.com/evanclan/OpenJLPT> (CC BY-SA 4.0).
Upstream data: Waller lists (CC BY), JMdict/KANJIDIC2 (EDRDG),
Tatoeba example sentences. 99.7% of words are linked to JMdict
entries; example sentences exist for ~93% of words.

The generated data (`src/nihongo/data.cljs`, built by
`scripts/build_data.py` from a clone in `data-raw/openjlpt/`,
not tracked) trims each entry: vocabulary meanings capped at 3
and the first example sentence only; kanji on'yomi/kun'yomi
capped at 3, meanings at 4, example words at 8 (with the word's
vocabulary reading looked up where the word is in the vocab set).

### 2. Grammar — Hanabira

Source: <https://github.com/tristcoil/hanabira.org> (MIT code,
Creative Commons in-house content). Grammar JSON in that repo at
`backend/express/json_data/grammar_ja_JLPT_N5..N1_0001.json`
(828 points, verified by reading the repo, not just the website
counts). The generated data keeps the title's pattern and romaji,
the short and long explanations, formation, and the first 2
example sentences. Hanabira's vocabulary lists are just the
Tanos lists again, so they add nothing over OpenJLPT and are
not used.

### 3. Kana

The 214 kana items are written out in `scripts/build_data.py`
itself: the 46 gojūon, dakuten and yōon for both hiragana and
katakana, with romaji. These are standard and uncontroversial.

### Regenerating the data

```sh
python3 scripts/build_data.py   # needs data-raw/openjlpt/ and data-raw/hanabira/
```

`data-raw/` is gitignored; the generated `src/nihongo/data.cljs`
is tracked, so a checkout builds without the raw sources.

## Build

Requires Node.js 24, npm, and Java 21+ (the Closure jar in
shadow-cljs 3.5.11 needs Java 21; Java 17 fails). React 19.3.1 +
Reagent 2.0.1 requires `reagent.dom.client/create-root`, not
`reagent.dom/render`.

```sh
npm install
npx shadow-cljs release app
# then open public/index.html, or: npx shadow-cljs watch app
```

In the container used to build this, Java cannot tunnel through
the egress proxy (Java's `HttpURLConnection` / Maven wagon fail
on the proxy's CONNECT response, while curl and Python through
the same proxy work fine), so `npx shadow-cljs release app` fails
while trying to download its Maven/Clojars dependencies. The
verified build therefore pre-downloads those dependencies with a
proxy-capable resolver and then runs shadow-cljs directly,
skipping Maven resolution:

```sh
# 1. Pre-download shadow-cljs + reagent dependencies into ~/.m2
python3 scripts/resolve_maven.py

# 2. Run shadow-cljs's compiler directly, with the downloaded jars
export JAVA_HOME=~/workspace/tools/jdk-21.0.13+9
export PATH=$JAVA_HOME/bin:$PATH
CP=$(find ~/.m2/repository -name '*.jar' ! -name '*-aot.jar' \
       ! -path '*thheller/shadow-cljs/*' | tr '\n' ':')
CP="$CP$HOME/.m2/repository/thheller/shadow-cljs/3.5.11/shadow-cljs-3.5.11-aot.jar:src"
java -cp "$CP" clojure.main -m shadow.cljs.devtools.cli release app
# [:app] Build completed. (79 files, 1 compiled, 0 warnings, 45.43s)
```

One source fix was found by that build and is already in the
tree: `src/nihongo/core.cljs` originally had an extra closing
paren in `make-options`; it now builds the distractor list with
a `let` instead.

## Project layout

- `public/index.html` — page shell, CSS, and the `<ruby>` sizing
  for furigana.
- `public/js/` — compiled output (built, not tracked).
- `src/nihongo/data.cljs` — generated data (11,236 items).
- `src/nihongo/storage.cljs` — localStorage accounts, EDN backup
  parse, and the 1/2/4/8/16/32-day review schedule.
- `src/nihongo/core.cljs` — the app: Learn, Quiz, Review, Browse,
  Accounts, furigana ruby rendering, and the quiz logic.
- `scripts/build_data.py` — regenerates `data.cljs` from
  OpenJLPT + Hanabira + the kana tables.
- `scripts/resolve_maven.py` — proxy-capable Maven pre-download
  (offline build).
- `shadow-cljs.edn` — shadow-cljs build, Reagent 2.0.1 dependency.
