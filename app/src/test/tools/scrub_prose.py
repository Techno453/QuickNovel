#!/usr/bin/env python3
"""Replace copyrighted prose in a recorded provider fixture with placeholder text.

Every CJK ideograph, kana and Hangul syllable inside the selected regions is swapped for a
neutral placeholder character of the same script (ideograph -> 甲乙丙..., hiragana -> あいう...,
katakana -> アイウ..., Hangul -> 가나다...). Tags, attributes, entities, punctuation, digits,
ASCII and whitespace are left alone, so the HTML skeleton, paragraph count, line lengths and
ruby structure (a kana reading stays kana) are unchanged and the parser runs the same code
paths; only the words are gone.

Usage:
  scrub_prose.py FILE --between START END [--between START END ...]
      Scrub the text between every occurrence of START and the next END after it (markers are
      plain substrings of the file; the markers themselves are kept as they are).
  scrub_prose.py FILE --json-key KEY [--json-key KEY ...]
      FILE is JSON; scrub every string value stored under one of the given keys, anywhere in
      the document (string values are treated as HTML fragments).

The file is rewritten in place as UTF-8. See app/src/test/resources/providers/README.md.
"""
import argparse
import json
import re
import sys

_IDEOGRAPHS = "甲乙丙丁戊己庚辛壬癸"
_HIRAGANA = "あいうえおかきくけこ"
_KATAKANA = "アイウエオカキクケコ"
_HANGUL = "가나다라마바사아자차"

# Tags, comments and character entities are copied through untouched.
_MARKUP = re.compile(r"<!--.*?-->|<[^>]*>|&#?\w+;", re.S)


def _placeholder(ch, i):
    o = ord(ch)
    if 0x4E00 <= o <= 0x9FFF or 0x3400 <= o <= 0x4DBF or 0xF900 <= o <= 0xFAFF:
        return _IDEOGRAPHS[i % len(_IDEOGRAPHS)]
    if 0x3041 <= o <= 0x3096:
        return _HIRAGANA[i % len(_HIRAGANA)]
    if 0x30A1 <= o <= 0x30FA:
        return _KATAKANA[i % len(_KATAKANA)]
    if 0xAC00 <= o <= 0xD7A3:
        return _HANGUL[i % len(_HANGUL)]
    return ch


def scrub_text(text):
    return "".join(_placeholder(ch, i) for i, ch in enumerate(text))


def scrub_html(fragment):
    out, pos = [], 0
    for m in _MARKUP.finditer(fragment):
        out.append(scrub_text(fragment[pos:m.start()]))
        out.append(m.group(0))
        pos = m.end()
    out.append(scrub_text(fragment[pos:]))
    return "".join(out)


def scrub_between(content, pairs):
    for start, end in pairs:
        pos, found = 0, False
        while True:
            s = content.find(start, pos)
            if s < 0:
                break
            found = True
            s += len(start)
            e = content.find(end, s)
            if e < 0:
                sys.exit(f"end marker not found after start: {end!r}")
            content = content[:s] + scrub_html(content[s:e]) + content[e:]
            pos = e + len(end)
        if not found:
            sys.exit(f"start marker not found: {start!r}")
    return content


def scrub_json(node, keys):
    if isinstance(node, dict):
        return {k: (scrub_html(v) if k in keys and isinstance(v, str) else scrub_json(v, keys))
                for k, v in node.items()}
    if isinstance(node, list):
        return [scrub_json(v, keys) for v in node]
    return node


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file")
    ap.add_argument("--between", nargs=2, action="append", metavar=("START", "END"), default=[])
    ap.add_argument("--json-key", action="append", default=[])
    args = ap.parse_args()
    if not args.between and not args.json_key:
        ap.error("give at least one --between or --json-key")

    with open(args.file, encoding="utf-8") as f:
        content = f.read()
    if args.json_key:
        content = json.dumps(scrub_json(json.loads(content), set(args.json_key)), ensure_ascii=False, indent=1)
    if args.between:
        content = scrub_between(content, args.between)
    with open(args.file, "w", encoding="utf-8", newline="") as f:
        f.write(content)


if __name__ == "__main__":
    main()
