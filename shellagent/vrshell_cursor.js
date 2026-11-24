// Injected (eternalized) into com.oculus.vrshell by the app's root watcher (Core.startShellCursor).
// Hides the Quest's own panel cursor over vrfrida only, so the app's custom cursor is the only one:
// vrshell's ShellApplication.nativeUpdateMorphCursorScale(scale, panelId) scales the cursor per panel; 0 = invisible.
// Which panel: the app puts its window's persistableToken (dumpsys volumetric_window) in the system property
// debug.vrfrida.cursor ("" = feature off). vrshell keeps VolumetricWindowToken -> [panelId] in the static map X.028.A02.
// Panels that stop matching (vrfrida closed, setting off) get scale 1 back, so no other app ever inherits it.
// Needs re-checking after a vrshell update (obfuscated names: X.028.A02).
import Java from "frida-java-bridge";

const PROP = "debug.vrfrida.cursor";
const getProp = new NativeFunction(Module.getGlobalExportByName("__system_property_get"), "int", ["pointer", "pointer"]);
const nameBuf = Memory.allocUtf8String(PROP), valBuf = Memory.alloc(128);
function prop() { getProp(nameBuf, valBuf); return valBuf.readUtf8String().trim(); }

let hidden = new Set(), lastWant = null, lastApply = 0;

function panelsFor(uuid) {
    const out = [];
    if (!uuid) return out;
    const M = Java.use("X.028");
    const map = (M._A02 !== undefined ? M._A02 : M.A02).value;
    if (!map) return out;
    const Entry = Java.use("java.util.Map$Entry"), List = Java.use("java.util.List");
    const it = map.entrySet().iterator();
    while (it.hasNext()) {
        const e = Java.cast(it.next(), Entry);
        if (String(e.getKey()).indexOf(uuid) < 0) continue;
        const l = Java.cast(e.getValue(), List);
        for (let i = 0; i < l.size(); i++) out.push(parseInt(String(l.get(i))));
    }
    return out;
}

function tick() {
    const want = prop();
    const now = Date.now();
    if (want === lastWant && now - lastApply < 3000) return;    // re-apply every 3 s (panels come and go, shell may reset)
    lastWant = want; lastApply = now;
    Java.perform(() => {
        const S = Java.use("com.oculus.vrshell.ShellApplication");
        const ids = panelsFor(want);
        for (const id of hidden) if (ids.indexOf(id) < 0) { try { S.nativeUpdateMorphCursorScale(1.0, id); } catch (e) {} hidden.delete(id); }
        for (const id of ids) { try { S.nativeUpdateMorphCursorScale(0.0, id); hidden.add(id); } catch (e) { console.log("[vrshell cursor] " + e); } }
    });
}

if (globalThis.__vrfridaCursor) clearInterval(globalThis.__vrfridaCursor);
globalThis.__vrfridaCursor = setInterval(() => { try { tick(); } catch (e) { console.log("[vrshell cursor] tick: " + e); } }, 700);
console.log("[vrshell cursor] loaded, property " + PROP + " = '" + prop() + "'");
