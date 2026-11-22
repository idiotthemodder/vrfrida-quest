#!/usr/bin/env python3
"""
gen_menu_assets.py <UniversalMenu.ts> <out dir>

Cuts the theme + background code out of UniversalMenu.ts (by text anchors, so menu edits carry over),
runs it in frida's JS runtime against a throwaway local process, and writes what the Quest app needs to
look like the menu:
    themes.json        every theme's computed imgui colors (+ 48 hue steps of the animated Rainbow theme)
    meta.json          BG_NAMES, BG_SETS, PAT_COLORS, pointer / click lists, defaults from the top constants
    bg/<layer>.png     each generated background layer (sky, clouds, petals, stars, aurora, ...)
    spiral.png         the Spiral Rings texture
Run with the frida-tools python (build.sh does this).
"""
import json, os, re, struct, subprocess, sys, tempfile, threading, zlib

import frida


def cut(src, start, end, name):
    i = src.find(start)
    j = src.find(end, i + 1) if i >= 0 else -1
    if i < 0 or j < 0:
        sys.exit("gen_menu_assets: couldn't find the %s section (%r .. %r) in UniversalMenu.ts" % (name, start, end))
    return src[i:j]


def block(src, start, name):
    """from `start` to its matching closing brace"""
    i = src.find(start)
    if i < 0:
        sys.exit("gen_menu_assets: couldn't find %s" % name)
    k = src.index("{", i)
    depth = 0
    for p in range(k, len(src)):
        if src[p] == "{":
            depth += 1
        elif src[p] == "}":
            depth -= 1
            if depth == 0:
                return src[i:p + 1]
    sys.exit("gen_menu_assets: unbalanced %s" % name)


def line(src, pattern, name):
    m = re.search(pattern, src, re.M)
    if not m:
        sys.exit("gen_menu_assets: couldn't find %s" % name)
    return m.group(0)


def png(path, w, h, rgba, flip=True):
    rows = [rgba[y * w * 4:(y + 1) * w * 4] for y in range(h)]
    if flip:            # unity textures start at the bottom row, png at the top
        rows.reverse()
    raw = b"".join(b"\x00" + r for r in rows)
    chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main():
    menu_path, out = sys.argv[1], sys.argv[2]
    src = open(menu_path, encoding="utf-8").read()
    parts = [
        "const ref = (v: any) => ({ v }); const style: any = { colors: {} }; const C: any = style.colors;",
        "const WINDOW_PATTERN = true; let pluginDir = ''; const listJs = (..._a: any[]): string[] => [];",
        block(src, "function hsv(", "hsv()"),
        cut(src, "const LEGACY_COLORS", "\tconst style = {", "legacy colors"),
        cut(src, "type RGB = number[];", "const themeCache", "theme registry"),
        cut(src, "const PAT_N = 384", "const CIRC_N", "spiral pattern"),
        line(src, r"^\s*const CURSOR_SHAPES = .*$", "CURSOR_SHAPES"),
        cut(src, "const SND_STYLES", "const CLICK_STYLES", "SND_STYLES"),   # CLICK_STYLES is built from it
        line(src, r"^\s*const CLICK_STYLES = .*$", "CLICK_STYLES"),
        cut(src, "const BG_NAMES", "let bgFailed", "backgrounds"),
    ]
    tail = r"""
    function themeOut(t: any) {
        return t.palette ? colorsFromPalette(t.palette, t.overrides, t.gradient) : addDerived(Object.assign({}, t.colors ?? {}, t.overrides ?? {}));
    }
    const themes = THEME_REGISTRY.map((t: any) => {
        const o: any = { id: t.id, name: t.name, colors: themeOut(t) };
        if (t.animated) { o.frames = []; for (let i = 0; i < 48; i++) o.frames.push(colorsFromRGB(rainbowPalette(i / 48))); }
        return o;
    });
    const patColors: any[] = [];
    for (let i = 0; i < PAT_COLORS.length; i++) { setPatColor.v = i; patColors.push(i === 0 || i === 5 ? null : patColor()); }
    send({ type: "json", name: "themes.json", data: { themes, aliases: THEME_ALIASES } });
    send({ type: "json", name: "meta.json", data: { BG_NAMES, BG_SETS, BG_GLOW, PAT_COLORS, patColors, CURSOR_SHAPES, CURSOR_COLORS, CLICK_STYLES } });
    send({ type: "img", name: "spiral.png", w: PAT_N, h: PAT_N }, patternBytes.buffer as ArrayBuffer);
    const ids: string[] = [];
    for (const set of BG_SETS) for (const l of set) if (!l.id.startsWith("pat:") && ids.indexOf(l.id) < 0) ids.push(l.id);
    for (const id of ids) { const g = bgGenerate(id); send({ type: "img", name: "bg/" + id + ".png", w: g.w, h: g.h, wrap: g.wrap }, g.bytes.buffer as ArrayBuffer); }
    send({ type: "done" });
    """
    ts = "\n".join(parts) + "\n" + tail

    # the menu's top-of-file defaults (what a missing settings.json falls back to)
    consts = {}
    for name in ("THEME", "ROUNDING", "WINDOW_WIDTH", "PAGE_HEIGHT", "SPIRAL_SPIN", "SPIRAL_CENTER", "RAY_PITCH_DEG", "SHOW_LASER",
                 "HUD_ENABLED", "WRIST_MENU", "ALWAYS_ON_TOP", "STABILIZE", "ROW_LAYOUT", "WINDOW_PATTERN", "MENU_HOLD_X", "MENU_TITLE"):
        m = re.search(r"^const %s\s*(?::\s*\w+)?\s*=\s*(.+?);" % name, src, re.M)
        if m:
            try:
                consts[name] = json.loads(m.group(1).strip())
            except ValueError:
                pass

    os.makedirs(os.path.join(out, "bg"), exist_ok=True)
    root = os.path.dirname(os.path.abspath(menu_path))
    with tempfile.NamedTemporaryFile("w", suffix=".ts", dir=root, prefix=".gen_menu_", delete=False) as f:
        f.write(ts)
        tmp_ts = f.name
    tmp_js = tmp_ts[:-3] + ".js"
    try:
        r = subprocess.run(["frida-compile", os.path.basename(tmp_ts), "-S", "-B", "iife", "-T", "none", "-o", tmp_js],
                           cwd=root, capture_output=True, text=True)
        if r.returncode != 0 or not os.path.isfile(tmp_js):
            sys.exit("gen_menu_assets: frida-compile failed:\n" + (r.stderr or r.stdout)[-2000:])
        js = open(tmp_js, encoding="utf-8").read()
    finally:
        for p in (tmp_ts, tmp_js):
            try:
                os.remove(p)
            except OSError:
                pass

    done = threading.Event()
    errors, written = [], []

    def on_message(msg, data):
        if msg["type"] == "error":
            errors.append(msg.get("stack") or msg.get("description"))
            done.set()
            return
        p = msg["payload"]
        if p["type"] == "json":
            if p["name"] == "meta.json":
                p["data"]["defaults"] = consts
            with open(os.path.join(out, p["name"]), "w") as f:
                json.dump(p["data"], f, separators=(",", ":"))
            written.append(p["name"])
        elif p["type"] == "img":
            png(os.path.join(out, p["name"]), p["w"], p["h"], data)
            written.append(p["name"])
        elif p["type"] == "done":
            done.set()

    pid = frida.spawn(["/usr/bin/sleep", "60"])
    try:
        session = frida.attach(pid)
        script = session.create_script(js, runtime="v8")
        script.on("message", on_message)
        script.load()
        if not done.wait(60):
            errors.append("timed out")
    finally:
        try:
            frida.kill(pid)
        except Exception:
            pass
    if errors:
        sys.exit("gen_menu_assets: script error: " + str(errors[0]))
    print("gen_menu_assets: wrote %d files to %s" % (len(written), out))
    sys.stdout.flush()
    os._exit(0)          # frida's teardown at interpreter exit can segfault; everything is written already


if __name__ == "__main__":
    main()
