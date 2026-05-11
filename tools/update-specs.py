#!/usr/bin/env python3
"""
ASTERIX spec updater for kafbat-ui-serde-asterix.

Downloads .ast specification files from zoranbosnjak/asterix-specs on GitHub,
parses them into a normalized JSON schema, and writes the result to
src/main/resources/asterix-specs/.

Usage:
    python3 tools/update-specs.py [--output-dir DIR] [--cat CAT] [--latest-only]

Environment:
    GITHUB_TOKEN  – optional, increases API rate limit

The script writes:
  {output_dir}/manifest.json          – index of all available specs
  {output_dir}/cat{N}/{edition}.json  – per-category, per-edition spec
"""

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone

# ─────────────────────────────────────────────────────────────────────────────
# GitHub helpers
# ─────────────────────────────────────────────────────────────────────────────

GITHUB_RAW = "https://raw.githubusercontent.com/zoranbosnjak/asterix-specs/master"
GITHUB_API = "https://api.github.com/repos/zoranbosnjak/asterix-specs"


def gh_get(url):
    req = urllib.request.Request(url)
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github.v3+json")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def raw_get(path):
    url = f"{GITHUB_RAW}/{path}"
    req = urllib.request.Request(url)
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8")


def list_specs():
    """Return {cat_num: [edition_str, ...]} from the upstream repo.

    Tries the GitHub Contents API first; on failure (rate-limit / no token)
    falls back to the hard-coded KNOWN_EDITIONS table and verifies each
    edition exists via a lightweight HEAD request on raw.githubusercontent.com.
    """
    cats = {}

    # Try API: list top-level cat directories
    try:
        entries = gh_get(f"{GITHUB_API}/contents/specs")
    except Exception as e:
        print(f"  WARNING: could not list specs via API ({e}), using built-in edition table",
              file=sys.stderr)
        entries = [{"name": f"cat{n:03d}"} for n in sorted(KNOWN_EDITIONS)]

    for entry in entries:
        name = entry["name"] if isinstance(entry, dict) else entry
        m = re.match(r"cat(\d+)$", name)
        if not m:
            continue
        cat_num = int(m.group(1))

        # Try API for per-category edition listing
        try:
            files = gh_get(f"{GITHUB_API}/contents/specs/{name}")
            editions = []
            for f in files:
                fm = re.match(r"cat-([0-9.]+)\.ast$", f["name"])
                if fm:
                    editions.append(fm.group(1))
            editions.sort(key=lambda v: tuple(int(x) for x in v.split(".")))
            if editions:
                cats[cat_num] = editions
                continue
        except Exception:
            pass  # fall through to known-editions table

        # Fallback: use hard-coded list, verify existence via raw URL
        if cat_num in KNOWN_EDITIONS:
            verified = []
            for ed in KNOWN_EDITIONS[cat_num]:
                url = (f"{GITHUB_RAW}/specs/cat{cat_num:03d}"
                       f"/cat-{ed}.ast")
                try:
                    req = urllib.request.Request(url, method="HEAD")
                    token = os.environ.get("GITHUB_TOKEN")
                    if token:
                        req.add_header("Authorization", f"Bearer {token}")
                    with urllib.request.urlopen(req, timeout=10):
                        verified.append(ed)
                except Exception:
                    pass
            if verified:
                cats[cat_num] = verified
                print(f"  INFO: used built-in editions for cat{cat_num}: {verified}",
                      file=sys.stderr)
        else:
            print(f"  WARNING: no known editions for cat{cat_num}, skipping",
                  file=sys.stderr)

    return cats


# Hard-coded edition table – used as fallback when the GitHub API is rate-limited.
# Keep this in sync with the upstream zoranbosnjak/asterix-specs repository.
KNOWN_EDITIONS = {
    1:   ["1.2", "1.3", "1.4"],
    2:   ["1.0", "1.1", "1.2"],
    7:   ["1.12"],
    8:   ["1.2", "1.3"],
    10:  ["1.1"],
    11:  ["1.2"],
    15:  ["1.0", "1.1"],
    16:  ["1.0"],
    17:  ["1.3"],
    18:  ["1.7", "1.8"],
    19:  ["1.3"],
    20:  ["1.9", "1.10", "1.11"],
    21:  ["2.1", "2.4", "2.6"],
    23:  ["1.2", "1.3"],
    25:  ["1.5", "1.6"],
    32:  ["1.1", "1.2"],
    34:  ["1.27", "1.28", "1.29"],
    48:  ["1.27", "1.28", "1.29", "1.30", "1.31", "1.32"],
    62:  ["1.18", "1.19", "1.20"],
    63:  ["1.6", "1.7"],
    65:  ["1.4", "1.5", "1.6"],
    205: ["1.0"],
    240: ["1.3"],
    247: ["1.2", "1.3"],
}

# ─────────────────────────────────────────────────────────────────────────────
# .ast parser
# ─────────────────────────────────────────────────────────────────────────────

class Reader:
    """Line-based reader with indentation awareness."""

    def __init__(self, text):
        raw = text.expandtabs(4).split("\n")
        # Drop blank lines and comment lines
        self.lines = [l for l in raw if l.strip() and not l.strip().startswith("--")]
        self.pos = 0

    def peek(self):
        while self.pos < len(self.lines):
            return self.lines[self.pos]
        return None

    def consume(self):
        line = self.lines[self.pos]
        self.pos += 1
        return line

    def indent(self, line=None):
        if line is None:
            line = self.peek()
        if line is None:
            return -1
        return len(line) - len(line.lstrip())

    def tokens(self, line):
        """Tokenize a line, respecting double-quoted strings."""
        result = []
        s = line.strip()
        i = 0
        while i < len(s):
            if s[i] == '"':
                j = s.find('"', i + 1)
                if j == -1:
                    j = len(s)
                result.append(s[i + 1:j])
                i = j + 1
            elif s[i] == ' ':
                i += 1
            else:
                j = i
                while j < len(s) and s[j] != ' ':
                    j += 1
                result.append(s[i:j])
                i = j
        return result

    def read_text_block(self, base_indent):
        """Consume and discard a block of lines deeper than base_indent."""
        while self.peek() and self.indent() > base_indent:
            self.consume()

    def peek_keyword(self):
        line = self.peek()
        if not line:
            return None
        return self.tokens(line)[0] if self.tokens(line) else None


def parse_lsb(frac_str):
    """Parse an LSB fraction like '1/2^7', '360/2^13', '1/4', '1'."""
    frac_str = frac_str.strip()
    m = re.match(r"^([0-9.]+)/2\^(-?[0-9]+)$", frac_str)
    if m:
        return float(m.group(1)) / (2.0 ** int(m.group(2)))
    m = re.match(r"^([0-9.]+)/([0-9.]+)$", frac_str)
    if m:
        return float(m.group(1)) / float(m.group(2))
    return float(frac_str)


def normalize_item_id(raw):
    """'010' → 'I010', 'SP' → 'SP', '-' → None."""
    special = {"SP", "RE", "FX", "rfs"}
    if raw == "-":
        return None
    if raw in special:
        return raw
    try:
        return f"I{int(raw):03d}"
    except ValueError:
        return raw


def parse_content(reader, content_indent):
    """Parse element content (raw / table / quantity / string / bds)."""
    line = reader.peek()
    if not line or reader.indent(line) < content_indent:
        return {"type": "Raw"}
    if reader.indent(line) != content_indent:
        return {"type": "Raw"}

    toks = reader.tokens(line)
    kw = toks[0]

    if kw == "raw":
        reader.consume()
        return {"type": "Raw"}

    elif kw in ("unsigned", "signed"):
        reader.consume()
        signed = kw == "signed"
        if len(toks) > 1 and toks[1] == "quantity":
            lsb = parse_lsb(toks[2]) if len(toks) > 2 else 1.0
            unit = toks[3] if len(toks) > 3 else ""
            return {"type": "Quantity", "signed": signed, "lsb": lsb, "unit": unit}
        else:
            # 'unsigned integer' / 'signed integer'
            return {"type": "Integer", "signed": signed}

    elif kw == "table":
        reader.consume()
        values = {}
        table_indent = content_indent + 4
        while reader.peek() and reader.indent(reader.peek()) >= table_indent:
            entry = reader.consume()
            et = reader.tokens(entry)
            if len(et) >= 1:
                code = et[0].rstrip(":")
                desc = " ".join(et[1:]) if len(et) > 1 else ""
                values[code] = desc
        return {"type": "Table", "values": values}

    elif kw == "string":
        reader.consume()
        variant = toks[1].capitalize() if len(toks) > 1 else "Ascii"
        return {"type": "String", "variant": variant}

    elif kw == "bds":
        reader.consume()
        return {"type": "Bds"}

    elif kw == "cf":
        reader.consume()
        reader.read_text_block(content_indent)
        return {"type": "Cf"}

    else:
        reader.consume()
        return {"type": "Raw"}


def parse_item_content(reader, parent_indent):
    """Parse the rule block for an item/sub-item whose name line was at parent_indent."""
    rule = None
    content_indent = parent_indent + 4

    while reader.peek():
        line = reader.peek()
        ind = reader.indent(line)
        if ind < content_indent:
            break
        if ind != content_indent:
            reader.consume()
            continue

        toks = reader.tokens(line)
        kw = toks[0]

        if kw in ("definition", "remark", "description"):
            reader.consume()
            reader.read_text_block(content_indent)

        elif kw == "group":
            reader.consume()
            rule = {"type": "Group", "items": parse_parts(reader, content_indent)}

        elif kw == "extended":
            reader.consume()
            rule = {"type": "Extended",
                    "groups": parse_extended_groups(reader, content_indent)}

        elif kw == "element":
            reader.consume()
            size = int(toks[1]) if len(toks) > 1 else 0
            content = parse_content(reader, content_indent + 4)
            rule = {"type": "Element", "size": size, "content": content}

        elif kw == "repetitive":
            reader.consume()
            rep_tok = toks[1] if len(toks) > 1 else "1"
            fx_mode = rep_tok == "fx"
            rep_size = 0 if fx_mode else int(rep_tok)
            sub = parse_item_content(reader, content_indent)
            rule = {"type": "Repetitive", "repSize": rep_size,
                    "fxMode": fx_mode, "rule": sub or {"type": "Unknown"}}

        elif kw == "compound":
            reader.consume()
            rule = {"type": "Compound",
                    "items": parse_parts(reader, content_indent)}

        elif kw == "explicit":
            reader.consume()
            rule = {"type": "Explicit"}

        else:
            reader.consume()

    return rule or {"type": "Unknown"}


def parse_parts(reader, parent_indent):
    """Parse named parts (sub-items, spares, FX markers) within a group/compound."""
    parts = []
    part_indent = parent_indent + 4

    while reader.peek():
        line = reader.peek()
        ind = reader.indent(line)
        if ind < part_indent:
            break
        if ind != part_indent:
            reader.consume()
            continue

        toks = reader.tokens(line)
        kw = toks[0]

        if kw == "spare":
            reader.consume()
            size = int(toks[1]) if len(toks) > 1 else 0
            parts.append({"type": "Spare", "size": size})

        elif kw == "-":
            reader.consume()
            parts.append({"type": "Fx"})

        elif kw in ("description", "remark"):
            reader.consume()
            reader.read_text_block(part_indent)

        else:
            name = kw
            title = toks[1] if len(toks) > 1 else ""
            reader.consume()
            sub_rule = parse_item_content(reader, part_indent)
            parts.append({"type": "Item", "name": name, "title": title,
                          "rule": sub_rule})

    return parts


def parse_extended_groups(reader, parent_indent):
    """Parse extended-item parts and group them by FX markers."""
    parts = parse_parts(reader, parent_indent)
    groups = [[]]
    for p in parts:
        if p["type"] == "Fx":
            groups.append([])
        else:
            groups[-1].append(p)
    while groups and not groups[-1]:
        groups.pop()
    return groups


def parse_items_section(reader, section_indent):
    """Parse the top-level 'items' section. Returns dict of itemId → spec."""
    items = {}
    item_indent = section_indent + 4

    while reader.peek():
        line = reader.peek()
        ind = reader.indent(line)
        if ind < item_indent:
            break
        if ind != item_indent:
            reader.consume()
            continue

        toks = reader.tokens(line)
        raw_id = toks[0]
        title = toks[1] if len(toks) > 1 else ""
        item_id = normalize_item_id(raw_id)
        reader.consume()

        rule = parse_item_content(reader, item_indent)
        if item_id:
            items[item_id] = {"title": title, "rule": rule}

    return items


def parse_uap_section(reader, uap_indent):
    """Parse the UAP section (uap or uaps variants)."""
    line = reader.peek()
    if not line or reader.indent(line) != uap_indent:
        return {"type": "flat", "items": []}

    toks = reader.tokens(line)
    kw = toks[0]

    if kw == "uap":
        reader.consume()
        items = []
        while reader.peek() and reader.indent(reader.peek()) > uap_indent:
            raw = reader.consume().strip()
            items.append(normalize_item_id(raw))
        return {"type": "flat", "items": items}

    elif kw == "uaps":
        reader.consume()
        # consume 'variations'
        if reader.peek() and reader.tokens(reader.peek())[0] == "variations":
            reader.consume()

        variants = {}
        var_indent = uap_indent + 4
        while reader.peek() and reader.indent(reader.peek()) >= var_indent:
            if reader.indent(reader.peek()) != var_indent:
                reader.consume()
                continue
            var_line = reader.consume()
            var_name = reader.tokens(var_line)[0]
            items = []
            item_indent2 = var_indent + 4
            while reader.peek() and reader.indent(reader.peek()) >= item_indent2:
                raw = reader.consume().strip()
                items.append(normalize_item_id(raw))
            variants[var_name] = items

        return {"type": "multi", "variants": variants}

    return {"type": "flat", "items": []}


def parse_ast(text):
    """Parse a full .ast file and return a normalized JSON dict."""
    reader = Reader(text)

    # ── Header ────────────────────────────────────────────────────────────────
    header = {}
    while reader.peek():
        line = reader.peek()
        toks = reader.tokens(line)
        kw = toks[0]

        if kw == "asterix":
            reader.consume()
            header["category"] = int(toks[1])
            header["name"] = toks[2] if len(toks) > 2 else ""
        elif kw == "edition":
            reader.consume()
            header["edition"] = toks[1] if len(toks) > 1 else ""
        elif kw == "date":
            reader.consume()
            header["date"] = toks[1] if len(toks) > 1 else ""
        elif kw == "preamble":
            reader.consume()
            reader.read_text_block(0)
        elif kw in ("items", "uap", "uaps"):
            break
        else:
            reader.consume()

    # ── Items ─────────────────────────────────────────────────────────────────
    items = {}
    if reader.peek() and reader.tokens(reader.peek())[0] == "items":
        reader.consume()
        items = parse_items_section(reader, 0)

    # ── UAP ───────────────────────────────────────────────────────────────────
    uap = parse_uap_section(reader, 0)

    return {
        "category": header.get("category", 0),
        "edition": header.get("edition", ""),
        "name": header.get("name", ""),
        "date": header.get("date", ""),
        "uap": uap,
        "items": items,
    }


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

def sha256(text):
    return hashlib.sha256(text.encode()).hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--output-dir", default="src/main/resources/asterix-specs",
                    help="Directory to write JSON specs")
    ap.add_argument("--cat", type=int, default=None,
                    help="Only update this category number")
    ap.add_argument("--latest-only", action="store_true",
                    help="Download only the latest edition per category (default: all editions)")
    args = ap.parse_args()

    out_dir = args.output_dir
    os.makedirs(out_dir, exist_ok=True)

    print("Listing available specs from upstream …")
    all_specs = list_specs()
    if not all_specs:
        print("ERROR: could not enumerate specs", file=sys.stderr)
        sys.exit(1)

    if args.cat:
        if args.cat not in all_specs:
            print(f"ERROR: cat{args.cat:03d} not found upstream", file=sys.stderr)
            sys.exit(1)
        all_specs = {args.cat: all_specs[args.cat]}

    manifest_entries = []
    changed = False

    for cat_num, editions in sorted(all_specs.items()):
        cat_dir = os.path.join(out_dir, f"cat{cat_num:03d}")
        os.makedirs(cat_dir, exist_ok=True)

        to_process = [editions[-1]] if args.latest_only else editions

        for edition in to_process:
            cat_str = f"cat{cat_num:03d}"
            ast_path = f"specs/{cat_str}/cat-{edition}.ast"
            out_file = os.path.join(cat_dir, f"{edition}.json")

            print(f"  {cat_str} edition {edition} … ", end="", flush=True)
            try:
                ast_text = raw_get(ast_path)
            except Exception as e:
                print(f"SKIP ({e})")
                continue

            spec = parse_ast(ast_text)
            spec_json = json.dumps(spec, indent=2, ensure_ascii=False)

            # Track changes
            new_hash = sha256(spec_json)
            old_hash = None
            if os.path.exists(out_file):
                with open(out_file) as f:
                    old_hash = sha256(f.read())

            if new_hash != old_hash:
                with open(out_file, "w") as f:
                    f.write(spec_json)
                changed = True
                print("updated")
            else:
                print("unchanged")

            is_latest = (edition == editions[-1])
            manifest_entries.append({
                "category": cat_num,
                "edition": edition,
                "name": spec["name"],
                "date": spec["date"],
                "file": f"cat{cat_num:03d}/{edition}.json",
                "latest": is_latest,
            })

    # Write manifest
    manifest = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "source": "https://github.com/zoranbosnjak/asterix-specs",
        "specs": manifest_entries,
    }
    manifest_path = os.path.join(out_dir, "manifest.json")
    manifest_json = json.dumps(manifest, indent=2)
    old_manifest = open(manifest_path).read() if os.path.exists(manifest_path) else ""
    if manifest_json.rstrip() != old_manifest.rstrip():
        with open(manifest_path, "w") as f:
            f.write(manifest_json)
        changed = True

    print()
    if changed:
        print("Specs changed — rebuild required.")
        sys.exit(0)
    else:
        print("No changes detected.")
        # Exit code 42 = no change (used by CI)
        sys.exit(42)


if __name__ == "__main__":
    main()
