#!/usr/bin/env python3
"""Ask `nls` what it knows at a cursor position, without an editor.

    ./lsp-probe.py <file.ncl> <line> <col>            # completions (1-based line, 0-based col)
    ./lsp-probe.py --hover <file.ncl> <line> <col>    # hover type
    ./lsp-probe.py --claims                           # re-run the README's claims

Exists because the interesting result in this directory is a NEGATIVE one —
completion after a function call returns nothing — and "my editor showed no
popup" is not evidence. This asks the server directly and prints what it said.
"""
import json
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.abspath(__file__))


def ask(method, path, line, col, timeout=8.0):
    """Send one textDocument/<method> request at (line, col) and return the result."""
    p = subprocess.Popen(
        ["nls"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )

    def send(m):
        b = json.dumps(m).encode()
        p.stdin.write(b"Content-Length: %d\r\n\r\n" % len(b) + b)
        p.stdin.flush()

    def read():
        head = b""
        while b"\r\n\r\n" not in head:
            ch = p.stdout.read(1)
            if not ch:
                return None
            head += ch
        n = int([l for l in head.decode().split("\r\n")
                 if l.lower().startswith("content-length")][0].split(":")[1])
        return json.loads(p.stdout.read(n))

    try:
        send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "processId": os.getpid(), "rootUri": "file://" + ROOT,
            "capabilities": {"textDocument": {"completion": {}, "hover": {}}}}})
        read()
        send({"jsonrpc": "2.0", "method": "initialized", "params": {}})

        full = os.path.join(ROOT, path)
        send({"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {
            "textDocument": {"uri": "file://" + full, "languageId": "nickel",
                             "text": open(full).read(), "version": 1}}})
        # nls indexes on open; asking too early yields a spurious empty result,
        # which is exactly the false negative this script is meant to avoid.
        time.sleep(2)

        send({"jsonrpc": "2.0", "id": 2, "method": "textDocument/" + method,
              "params": {"textDocument": {"uri": "file://" + full},
                         "position": {"line": line - 1, "character": col},
                         "context": {"triggerKind": 2, "triggerCharacter": "."}}})

        deadline = time.time() + timeout
        while time.time() < deadline:
            m = read()
            if m is None:
                break
            if m.get("id") == 2:
                return m.get("result")
        return None
    finally:
        p.kill()


def completions(path, line, col):
    r = ask("completion", path, line, col) or []
    items = r if isinstance(r, list) else r.get("items", [])
    return sorted(i.get("label") for i in items)


def hover(path, line, col):
    r = ask("hover", path, line, col)
    if not r:
        return None
    c = r.get("contents")
    if isinstance(c, list) and c:
        return c[0].get("value")
    return json.dumps(c)


# (description, file, line, col, expectation) — the README's claims, as code.
COMPLETION_CLAIMS = [
    ("module record       q.", "probe.ncl", 8, 8,
     "the namespaces: builder, expr, pipe"),
    ("nested record       q.pipe.", "probe.ncl", 9, 13,
     "the pipe-style builder steps"),
    ("dump, light WITH    home.entities.light_hue_bibliotek.", "probe.ncl", 10, 40,
     "exactly this light's fields, colourTemp INCLUDED"),
    ("dump, light WITHOUT home.entities.light_plug.", "probe.ncl", 11, 31,
     "exactly this light's fields, colourTemp ABSENT"),
    ("function result     (q.builder.from home.lights).", "probe.ncl", 12, 35,
     "the builder steps -- this is the claim to check"),
    ("function result     (c.toggle home.entities.light_plug).", "probe.ncl", 13, 42,
     "a node's fields -- same claim, component library"),
]

# Same question, asked of hover: does the annotation reach the editor at all?
# Asked of dashboard.ncl, which PARSES — nls degrades to `Dyn` after the first
# parse error in a file, so asking these of probe.ncl gives a false negative.
HOVER_CLAIMS = [
    ("q.pipe.where", "dashboard.ncl", 17, 18),
    ("c.fullWidth", "dashboard.ncl", 21, 12),
    ("c.slider      (tagged ADT param, no Dyn)", "dashboard.ncl", 25, 10),
    ("c.button", "dashboard.ncl", 27, 10),
    ("c.slider      SAME symbol, asked of the unparseable probe.ncl", "probe.ncl", 14, 10),
]


def main():
    args = sys.argv[1:]
    if args == ["--claims"]:
        print("=== completion " + "=" * 50)
        for desc, f, line, col, expect in COMPLETION_CLAIMS:
            print(f"{desc}\n    expect: {expect}\n    got:    {completions(f, line, col)}\n")
        print("=== hover " + "=" * 55)
        for desc, f, line, col in HOVER_CLAIMS:
            print(f"{desc}\n    got:    {hover(f, line, col)}\n")
        return
    if len(args) == 4 and args[0] == "--hover":
        print(hover(args[1], int(args[2]), int(args[3])))
        return
    if len(args) == 3:
        print(completions(args[0], int(args[1]), int(args[2])))
        return
    print(__doc__)
    sys.exit(2)


if __name__ == "__main__":
    main()
