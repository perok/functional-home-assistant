#!/usr/bin/env python3
"""Ask `nls` what it knows at a cursor position, without an editor.

    ./lsp-probe.py <file.ncl> <line> <col>            # completions (1-based line, 0-based col)
    ./lsp-probe.py --hover <file.ncl> <line> <col>    # hover type
    ./lsp-probe.py --diagnostics <file.ncl>           # what the editor underlines
    ./lsp-probe.py --claims                           # re-run the README's claims

Exists because "my editor showed a popup" (or did not) is not evidence. This
asks the server directly and prints what it said.

Two confounds it is built to avoid, both of which produced wrong conclusions
here: nls needs a moment to index after didOpen, and it degrades badly after the
FIRST parse error in a file. Anything measuring a call site must therefore use a
file that parses — probe-static.ncl, not probe.ncl.
"""
import json
import os
import select
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


def diagnostics(path, wait=10.0):
    """What nls PUBLISHES for a file — i.e. what the editor underlines.

    Separate from ask() because diagnostics are unsolicited notifications, and
    the interesting answer is "none ever arrived": a blocking read would hang
    on exactly the result worth measuring, so this polls to a deadline.
    """
    p = subprocess.Popen(
        ["nls"], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )

    def send(m):
        b = json.dumps(m).encode()
        p.stdin.write(b"Content-Length: %d\r\n\r\n" % len(b) + b)
        p.stdin.flush()

    full = os.path.join(ROOT, path)
    out = []
    try:
        send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "processId": os.getpid(), "rootUri": "file://" + ROOT, "capabilities": {}}})
        send({"jsonrpc": "2.0", "method": "initialized", "params": {}})
        send({"jsonrpc": "2.0", "method": "textDocument/didOpen", "params": {
            "textDocument": {"uri": "file://" + full, "languageId": "nickel",
                             "text": open(full).read(), "version": 1}}})

        buf, deadline = b"", time.time() + wait
        while time.time() < deadline:
            if select.select([p.stdout], [], [], 0.5)[0]:
                buf += os.read(p.stdout.fileno(), 65536)
            while b"\r\n\r\n" in buf:
                head, rest = buf.split(b"\r\n\r\n", 1)
                n = int([l for l in head.decode().split("\r\n")
                         if l.lower().startswith("content-length")][0].split(":")[1])
                if len(rest) < n:
                    break
                m, buf = json.loads(rest[:n]), rest[n:]
                if m.get("method") == "textDocument/publishDiagnostics":
                    out += [d.get("message", "").split("\n")[0]
                            for d in m["params"]["diagnostics"]]
        return out
    finally:
        p.kill()


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
    ("module record       q.", "probe.ncl", 11, 8,
     "the namespaces: builder, expr, pipe"),
    ("nested record       q.pipe.", "probe.ncl", 12, 13,
     "the pipe-style builder steps"),
    ("dump, light WITH    home.entities.light_hue_bibliotek.", "probe.ncl", 13, 40,
     "exactly this light's fields, colourTemp INCLUDED"),
    ("dump, light WITHOUT home.entities.light_plug.", "probe.ncl", 14, 31,
     "exactly this light's fields, colourTemp ABSENT"),
    # These MUST be asked of a file that parses -- see the module docstring.
    ("call result         (c.toggle …).", "probe-static.ncl", 12, 42,
     "cell + body -- does completion follow a RETURN TYPE across an import?"),
    ("two levels deep     (c.toggle …).body.", "probe-static.ncl", 13, 47,
     "the node's own fields"),
    ("polymorphic result  (core.fullWidth …).", "probe-static.ncl", 14, 59,
     "cell + body, through a forall"),
    ("capability          …colourTemp.", "probe-static.ncl", 15, 51,
     "the axis fields, on a light that HAS one"),
]

# Same question, asked of hover: does the annotation reach the editor at all?
# Asked of dashboard.ncl, which PARSES — nls degrades to `Dyn` after the first
# parse error in a file, so asking these of probe.ncl gives a false negative.
HOVER_CLAIMS = [
    ("c.toggle", "probe-static.ncl", 12, 10),
    ("core.fullWidth   (forall, instantiated)", "probe-static.ncl", 14, 14),
    # Controls: the two ways hover collapses to Dyn.
    ("c.slider         INSIDE a `| Dyn` region (dashboard.ncl)", "dashboard.ncl", 33, 10),
    ("c.slider         in the unparseable probe.ncl", "probe.ncl", 17, 10),
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
    if len(args) == 2 and args[0] == "--diagnostics":
        ds = diagnostics(args[1])
        for d in ds:
            print(d)
        # Exit code so a claims script can assert "the editor is silent here".
        sys.exit(1 if ds else 0)
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
