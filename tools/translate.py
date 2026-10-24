#!/usr/bin/env python3
"""Translation catalogues for the Android app and the LG/Samsung web app (app/assets/i18n/<lang>.json).

    python3 tools/translate.py                 # extract en.json from the code, report what each language lacks
    python3 tools/translate.py --fill          # also translate the missing strings (needs ANTHROPIC_API_KEY)
    python3 tools/translate.py --fill --lang fr --model claude-haiku-5-5
    python3 tools/translate.py --check         # validate all catalogues without writing anything
    python3 tools/translate.py --prune         # also remove keys the code no longer uses
    python3 tools/translate.py --new fr        # start a catalogue for a new language (then --fill --lang fr)

The English text in the code is the source: every Ui.tr("...") / Ui.trf("...", ...) / Ui.plural("...", n) call (the
first argument), plus Ui.datePattern("format|..."), and in web/ the same calls without "Ui." (tr, trf, plural and
formatDate; tools/webapp.py packs the keys the web app uses into its i18n.js). en.json maps each key to its English
text: a key "context|Text" is "Text" in a given context (a word that needs another translation there), and "{name}"
is a placeholder filled at runtime. A key "plural|one:{n} season|other:{n} seasons" (Ui.plural) is a count: each language
gives one form per plural category it uses (CLDR: zero, one, two, few, many, other). Each <lang>.json maps the same keys
to that language.

--fill sends only the strings (with their context word; no code, keys or settings) to the Anthropic Messages API,
and only the ones a catalogue lacks (or whose placeholders don't match the English). Every translation it gets back
is kept in tools/.i18n-cache.json under a hash of its key (context + English), so the same text is never sent twice.
Without ANTHROPIC_API_KEY it only reports, and exits with 1 when a catalogue is incomplete. Standard library only.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src")
WEB = os.path.join(ROOT, "web")
I18N = os.path.join(ROOT, "app", "assets", "i18n")
CACHE = os.path.join(ROOT, "tools", ".i18n-cache.json")   # translations made with --fill, by hash (not committed)
DEFAULT_MODEL = "claude-haiku-5-5"
CALL = re.compile(r"\bUi\.(trf?|plural|datePattern)\(")
WEB_CALL = re.compile(r"(?:(?<![\w.$])|(?<=\bI18N\.))(trf?|plural|formatDate)\(")
PLURAL_FORM = re.compile(r"^(zero|one|two|few|many|other):")
PLACEHOLDER = re.compile(r"\{[a-zA-Z_][a-zA-Z_0-9]*\}")


# MARK: - Extraction

def java_args(text, start):
    """Splits the arguments of the call whose '(' ends at start; returns (list of raw args, end index)."""
    args, depth, i, cur = [], 0, start, []
    while i < len(text):
        ch = text[i]
        if ch == '"':
            j = i + 1
            while text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            cur.append(text[i:j + 1])
            i = j + 1
            continue
        if ch == "'":
            j = i + 1
            while text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            cur.append(text[i:j + 1])
            i = j + 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                args.append("".join(cur).strip())
                return args, i
            depth -= 1
        elif ch == "," and depth == 0:
            args.append("".join(cur).strip())
            cur = []
            i += 1
            continue
        cur.append(ch)
        i += 1
    raise ValueError("unbalanced call")


LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
JS_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"|\'((?:[^\'\\]|\\.)*)\'')


def java_string(arg, literal=LITERAL):
    """The value of an argument made only of string literals joined with +, else None."""
    rest, out = arg.strip(), []
    while rest:
        m = literal.match(rest)
        if not m:
            return None
        out.append(next(g for g in m.groups() if g is not None))
        rest = rest[m.end():].strip()
        if rest.startswith("+"):
            rest = rest[1:].strip()
        elif rest:
            return None
    raw = "".join(out)
    return re.sub(r"\\(u[0-9a-fA-F]{4}|.)", lambda m: chr(int(m.group(1)[1:], 16)) if m.group(1)[0] == "u"
                  else {"n": "\n", "t": "\t"}.get(m.group(1), m.group(1)), raw)


def calls():
    """Every translatable call in the code: yields (file, line, kind, [argument values or None])."""
    yield from java_calls()
    yield from web_calls()


def java_calls():
    for dirpath, _, files in os.walk(SRC):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            text = open(path, encoding="utf-8").read()
            for m in CALL.finditer(text):
                line = text.count("\n", 0, m.start()) + 1
                prefix = text[text.rfind("\n", 0, m.start()) + 1:m.start()].lstrip()
                if prefix.startswith(("*", "//", "/*")):
                    continue   # an example in a comment
                args, _ = java_args(text, m.end())
                yield os.path.relpath(path, ROOT), line, m.group(1), [java_string(a) for a in args]


def web_calls():
    for name in sorted(os.listdir(WEB)):
        if not name.endswith(".js") or name == "strings.js":   # strings.js is where the calls are made, not used
            continue
        path = os.path.join(WEB, name)
        text = open(path, encoding="utf-8").read()
        for m in WEB_CALL.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            prefix = text[text.rfind("\n", 0, m.start()) + 1:m.start()].lstrip()
            if prefix.startswith(("*", "//", "/*")) or prefix.endswith("function "):
                continue
            args, _ = java_args(text, m.end())
            yield os.path.relpath(path, ROOT), line, m.group(1), [java_string(a, JS_LITERAL) for a in args]


def web_keys():
    """The keys the web app uses, with the plain text of each "context|Text" one (its fallback)."""
    keys = set()
    for _, _, _, args in web_calls():
        if args and args[0] is not None:
            keys.add(args[0])
            if "|" in args[0]:
                keys.add(english_of(args[0]))
    return keys


def english_of(key):
    return key.split("|", 1)[1] if "|" in key else key


def extract():
    """{key: English text} from the code; warns about calls whose text isn't a literal."""
    keys = {}
    for path, line, kind, args in calls():
        key = args[0]
        if key is None:
            print(f"warning: {path}:{line}: {kind} with text that isn't a string literal", file=sys.stderr)
            continue
        keys[key] = english_of(key)
    return dict(sorted(keys.items()))


# MARK: - Catalogues

def load(lang):
    with open(os.path.join(I18N, lang + ".json"), encoding="utf-8") as f:
        return json.load(f)


def save(lang, data):
    with open(os.path.join(I18N, lang + ".json"), "w", encoding="utf-8") as f:
        json.dump(dict(sorted(data.items())), f, ensure_ascii=False, indent=1)
        f.write("\n")


def languages():
    return sorted(n[:-5] for n in os.listdir(I18N) if n.endswith(".json") and n != "en.json")


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


def placeholders(text):
    return sorted(set(PLACEHOLDER.findall(text)))


def valid_format(text):
    """SimpleDateFormat letters outside quotes; doubled quotes are literal quotes."""
    quoted, i = False, 0
    while i < len(text):
        ch = text[i]
        if ch == "'":
            if i + 1 < len(text) and text[i + 1] == "'":
                i += 2
                continue
            quoted = not quoted
        elif not quoted and ch.isascii() and ch.isalpha():
            if ch not in "GyYMLwWDdFEuaHkKhmsSzZX":
                return False
            if ch == "X" and text[i:i + 4] == "XXXX":
                return False
        i += 1
    return not quoted


def fits(key, english, text):
    """The translation keeps the English placeholders; a plural is forms like "one:…|few:…|other:…", each with them."""
    if not isinstance(text, str):
        return False
    if key.startswith("format|") and not valid_format(text):
        return False
    if not key.startswith("plural|"):
        return placeholders(text) == placeholders(english)
    want = sorted(set(PLACEHOLDER.findall(english)))
    forms = text.split("|")
    return all(PLURAL_FORM.match(f) and sorted(set(PLACEHOLDER.findall(f))) == want for f in forms) \
        and all("{n}" in f for f in forms) and any(f.startswith("other:") for f in forms)


def problems(en, cat):
    missing = [k for k in en if k not in cat]
    obsolete = [k for k in cat if k not in en]
    broken = [k for k in en if k in cat and not fits(k, en[k], cat[k])]
    return missing, obsolete, broken


def check(en):
    errors = []
    for lang in ["en"] + languages():
        try:
            cat = load(lang)
        except (ValueError, OSError) as e:
            errors.append(f"{lang}: invalid JSON: {e}")
            continue
        if not isinstance(cat, dict):
            errors.append(f"{lang}: catalogue must be a JSON object")
            continue
        missing, obsolete, _ = problems(en, cat)
        for label, keys in (("missing key", missing), ("obsolete key", obsolete)):
            for key in keys:
                errors.append(f"{lang}: {label}: {key!r}")
        for key, value in cat.items():
            if not isinstance(value, str):
                errors.append(f"{lang}: non-string value: {key!r}")
            elif key.startswith("format|") and not valid_format(value):
                errors.append(f"{lang}: invalid format: {key!r}: {value!r}")
            elif key.startswith("plural|") and not fits(key, english_of(key), value):
                errors.append(f"{lang}: invalid plural forms: {key!r}")
            elif key in en and placeholders(value) != placeholders(en[key]):
                errors.append(f"{lang}: placeholder mismatch: {key!r}")
            if lang == "en" and key in en and value != en[key]:
                errors.append(f"en: out-of-date value: {key!r}")
    if errors:
        print("\n".join(errors))
        return 1
    print(f"OK: all catalogues match the code ({len(en)} keys).")
    return 0


# MARK: - Translation (Anthropic Messages API, raw HTTP)

NAMES = {"pt": "Brazilian Portuguese", "es": "Spanish", "de": "German", "fr": "French", "it": "Italian", "nl": "Dutch",
         "pl": "Polish", "sv": "Swedish", "da": "Danish", "nb": "Norwegian Bokmål", "fi": "Finnish", "cs": "Czech",
         "ro": "Romanian", "tr": "Turkish", "ru": "Russian", "uk": "Ukrainian", "el": "Greek", "ja": "Japanese",
         "ko": "Korean", "zh-CN": "Simplified Chinese", "zh-TW": "Traditional Chinese (Taiwan)", "hi": "Hindi", "id": "Indonesian", "vi": "Vietnamese",
         "hu": "Hungarian", "bg": "Bulgarian", "hr": "Croatian", "sk": "Slovak", "th": "Thai", "ms": "Malay"}

PROMPT = """Translate the user interface strings of a TV home screen app ("Free My TV") from English into {name} ({lang}).
Rules:
- Keep each translation short, about as long as the English: it is drawn on a TV screen in fixed places.
- Friendly, plain, everyday words, like the TV's own menus in {name}. Address the viewer informally if the language usually does on TV.
- Keep every placeholder like {{n}} or {{app}} exactly as written (they are filled in at runtime); move them where the grammar needs them.
  {{ago}} is already a full phrase in {name} ("12 min ago"), {{list}} a comma-separated list of things, {{error}} an error message,
  {{app}} an app's name, {{time}} a clock time, {{mode}} "Free My TV" or "Original".
- A "context" note says where the string appears. Context "format" means the text is a java.text.SimpleDateFormat pattern: give the usual pattern for {name} instead of a translation.
- Avoid plural agreement with a number placeholder when the language needs several plural forms: use a form that reads right for any number (an abbreviation, or "label: {{n}}").
- Context "plural" means the text is a count in plural forms, "one:…|other:…": answer with one form per Unicode CLDR plural category {name} uses for whole numbers, joined by "|" and always including "other" (Russian: "one:{{n}} сезон|few:{{n}} сезона|many:{{n}} сезонов|other:{{n}} сезона"; Japanese: "other:{{n}}シーズン").
- Keep product and brand names (Free My TV, Netflix, YouTube, TMDB, HDMI, Android, Claude Code) as they are.
Answer with a JSON object only, mapping each id to its translation."""


def translate(lang, items, model, key):
    """items: {id: (english, context)} -> {id: translation}."""
    lines = {i: ({"text": en, "context": ctx} if ctx else {"text": en}) for i, (en, ctx) in items.items()}
    body = {
        "model": model,
        "max_tokens": 16000,
        "system": PROMPT.format(name=NAMES.get(lang, lang), lang=lang),
        "messages": [{"role": "user", "content": json.dumps(lines, ensure_ascii=False, indent=1)}],
    }
    req = urllib.request.Request("https://api.anthropic.com/v1/messages", data=json.dumps(body).encode("utf-8"),
                                 headers={"x-api-key": key, "anthropic-version": "2023-06-01",
                                          "content-type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            reply = json.load(r)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"Anthropic API: HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:300]}")
    if reply.get("stop_reason") not in ("end_turn", "stop_sequence"):
        raise SystemExit(f"Anthropic API: stopped with {reply.get('stop_reason')}")
    text = "".join(b.get("text", "") for b in reply.get("content", []) if b.get("type") == "text").strip()
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text)
    return json.loads(text)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="validate all catalogues without writing anything")
    ap.add_argument("--fill", action="store_true", help="translate the missing strings (needs ANTHROPIC_API_KEY)")
    ap.add_argument("--prune", action="store_true", help="remove keys the code no longer uses")
    ap.add_argument("--lang", action="append", help="only these languages (repeatable)")
    ap.add_argument("--new", metavar="LANG", help="create an empty catalogue for LANG (e.g. fr, pt, zh-CN)")
    ap.add_argument("--model", default=os.environ.get("TRANSLATE_MODEL", DEFAULT_MODEL))
    a = ap.parse_args()

    en = extract()
    if a.check:
        return check(en)
    os.makedirs(I18N, exist_ok=True)
    save("en", en)
    if a.new and not os.path.exists(os.path.join(I18N, a.new + ".json")):
        save(a.new, {})
    cache = json.load(open(CACHE, encoding="utf-8")) if os.path.exists(CACHE) else {}
    key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if a.fill and not key:
        print("ANTHROPIC_API_KEY isn't set: reporting only.")
    print(f"en.json: {len(en)} strings")
    incomplete = 0
    for lang in a.lang or languages():
        cat = load(lang)
        missing, obsolete, broken = problems(en, cat)
        todo = sorted(set(missing) | set(broken))
        if a.fill and key and todo:
            known = cache.setdefault(lang, {})
            for k in todo:   # translated before (same English, same context): no need to ask again
                if digest(k) in known and fits(k, en[k], known[digest(k)]):
                    cat[k] = known[digest(k)]
            ask = [k for k in todo if k not in cat or not fits(k, en[k], cat[k])]
            for start in range(0, len(ask), 80):
                chunk = ask[start:start + 80]
                items = {str(i): (en[k], k.split("|", 1)[0] if "|" in k else "") for i, k in enumerate(chunk)}
                for i, text in translate(lang, items, a.model, key).items():
                    k = chunk[int(i)] if i.isdigit() and int(i) < len(chunk) else None
                    if k and isinstance(text, str) and fits(k, en[k], text):
                        cat[k] = text
                        known[digest(k)] = text
            print(f"{lang}: {len(todo) - len(ask)} from the cache, {len(ask)} sent to {a.model}")
        if a.prune:
            for k in obsolete:
                cat.pop(k)
        save(lang, cat)
        missing, obsolete, broken = problems(en, cat)
        status = "complete" if not (missing or broken) else "INCOMPLETE"
        incomplete += status != "complete"
        print(f"{lang:6} {len(cat) - len(obsolete):4}/{len(en)}  {status}"
              + (f"  missing {len(missing)}" if missing else "")
              + (f"  broken values {len(broken)}" if broken else "")
              + (f"  obsolete {len(obsolete)} (--prune)" if obsolete else ""))
        for k in (missing + broken)[:8]:
            print(f"         {k!r}")
    if cache:
        with open(CACHE, "w", encoding="utf-8") as f:
            json.dump(cache, f, ensure_ascii=False, indent=1, sort_keys=True)
    return 1 if incomplete else 0


if __name__ == "__main__":
    sys.exit(main())
