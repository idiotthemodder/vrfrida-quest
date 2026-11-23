// sysmic.js - vrfrida's system-wide mic soundboard (root). Injected into audioserver by the vrfrida app.
// Every app that records the mic (Meta party chat, every game's voice chat, recorders) reads it through
// audioflinger's RecordThread, which pulls each block from the HAL (StreamInHalHidl::read).
// This adds the sound to that block before audioflinger hands it to the apps, so they all get it as your voice.
//
// Commands come one per line on frida-inject's stdin (-i), into rpc.exports.onFridaStdin:
//   {"cmd":"fmt","sizes":{"7680":[8,3,48000]}}  HAL buffer bytes -> [frame size, audio_format_t, rate] (from dumpsys)
//   {"cmd":"load","rate":24000}            start a new sound (16-bit mono PCM follows)
//   {"cmd":"chunk","b64":"..."}            more of its samples, base64 little-endian int16
//   {"cmd":"play"} / {"cmd":"stop"}
//   {"cmd":"set","vol":1,"mute":false,"loop":false}
//   {"cmd":"status"}
// Status lines are printed as "[sysmic] ..." (frida-inject writes them to the log the app reads).

var S = { vol: 1, mute: false, loop: false,
          load: null, loadRate: 24000, loadLen: 0, pcm: null, srcRate: 24000, cur: 0,
          reads: 0, mixed: 0, lastRead: 0, err: "" };

function say(m) { console.log("[sysmic] " + m); }

var B64 = new Int16Array(128);
(function () { var a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"; for (var i = 0; i < 128; i++) B64[i] = -1; for (var j = 0; j < 64; j++) B64[a.charCodeAt(j)] = j; })();
function b64bytes(s) {
    s = s.replace(/[^A-Za-z0-9+/]/g, "");
    var n = Math.floor(s.length * 3 / 4), out = new Uint8Array(n), o = 0, i = 0;
    for (; i + 3 < s.length; i += 4) {
        var v = (B64[s.charCodeAt(i)] << 18) | (B64[s.charCodeAt(i + 1)] << 12) | (B64[s.charCodeAt(i + 2)] << 6) | B64[s.charCodeAt(i + 3)];
        out[o++] = v >> 16 & 255; out[o++] = v >> 8 & 255; out[o++] = v & 255;
    }
    var rest = s.length - i;
    if (rest >= 2) {
        var w = (B64[s.charCodeAt(i)] << 18) | (B64[s.charCodeAt(i + 1)] << 12) | (rest > 2 ? B64[s.charCodeAt(i + 2)] << 6 : 0);
        out[o++] = w >> 16 & 255; if (rest > 2) out[o++] = w >> 8 & 255;
    }
    return out.subarray(0, o);
}

function chunk(b64) {
    if (!S.load) return;
    var b = b64bytes(b64), n = b.length >> 1;
    if (S.loadLen + n > S.load.length) {           // grow
        var bigger = new Float32Array(Math.max(S.load.length * 2, S.loadLen + n));
        bigger.set(S.load.subarray(0, S.loadLen)); S.load = bigger;
    }
    var dv = new DataView(b.buffer, b.byteOffset, n * 2);
    for (var i = 0; i < n; i++) S.load[S.loadLen + i] = dv.getInt16(i * 2, true) / 32768;
    S.loadLen += n;
}

// mix into one HAL block: interleaved ch channels, 16 / 24-in-32 / 32 bit ints or float
function mix(ptr, frames, ch, bits, rate) {
    var src = S.pcm; if (!src) return;
    var isFloat = bits === 0;
    var bps = bits === 16 ? 2 : 4, n = frames * ch;
    var ab = ptr.readByteArray(n * bps);
    var a = bits === 16 ? new Int16Array(ab) : isFloat ? new Float32Array(ab) : new Int32Array(ab);
    var scale = bits === 16 ? 32767 : bits === 24 ? 8388607 : isFloat ? 1 : 2147483647;
    var step = S.srcRate / rate, L = src.length, cur = S.cur, vol = S.vol, mute = S.mute;
    for (var i = 0; i < frames; i++) {
        var s = 0;
        if (src) {
            var k = cur | 0;
            if (k < L - 1) { var t = cur - k; s = (src[k] + (src[k + 1] - src[k]) * t) * vol; }
            else if (k < L) s = src[k] * vol;
            cur += step;
            if (cur >= L) { if (S.loop) cur = 0; else { src = null; S.pcm = null; say("done"); } }
        } else if (!mute) break;
        for (var c = 0; c < ch; c++) {
            var j = i * ch + c, v = (mute ? 0 : a[j] / scale) + s;
            v = v > 1 ? 1 : v < -1 ? -1 : v;
            a[j] = isFloat ? v : Math.round(v * scale);
        }
    }
    S.cur = cur;
    ptr.writeByteArray(ab);
    S.mixed++;
}

// RecordThread reads each block straight from the HAL stream: StreamInHalHidl::read(buffer, bytes, size_t* read)
// (StreamInHalAidl on newer firmware). It isn't exported, but frida finds it in the library's MiniDebugInfo symbols.
// The stream doesn't say its format there, so vrfrida sends a table from dumpsys media.audio_flinger:
// HAL buffer size -> [frame size, audio_format_t]. A block whose size isn't in it is left alone, and the mix never
// touches more than the bytes the HAL actually returned.
var BITS = { 1: 16, 3: 32, 4: 24, 5: 0 };   // AUDIO_FORMAT_PCM_16_BIT, PCM_32_BIT, PCM_8_24_BIT, PCM_FLOAT (0 = float)
S.sizes = {}; S.fmt = ""; S.skipped = "";
function findRead() {
    var mods = Process.enumerateModules().filter(function (m) { return /^libaudiohal(@[0-9.]+|@aidl|_aidl)?\.so$/.test(m.name); });
    for (var i = 0; i < mods.length; i++) {
        var syms = [];
        try { syms = mods[i].enumerateSymbols(); } catch (e) { }
        for (var j = 0; j < syms.length; j++)
            if (/^_ZN7android15StreamInHal(Hidl|Aidl)4readEPvmPm$/.test(syms[j].name)) return { at: syms[j].address, where: mods[i].name + " " + syms[j].name };
    }
    return null;
}
var hal = findRead(), rd = hal ? hal.at : null;
if (!hal) say("error: StreamInHal*::read not found in libaudiohal, can't hook the mic");
else Interceptor.attach(hal.at, {
    onEnter: function (args) { this.buf = args[1]; this.bytes = args[2].toUInt32(); this.got = args[3]; },
    onLeave: function (ret) {
        S.reads++; S.lastRead = Date.now();
        if (!S.pcm) return;
        try {
            if (ret.toInt32() !== 0 || !this.got || this.got.isNull()) return;
            var n = this.got.readU64().toNumber(), f = S.sizes[this.bytes] || S.sizes["*"];
            if (!f) { if (S.skipped !== "" + this.bytes) { S.skipped = "" + this.bytes; say("unknown block size " + this.bytes + ", not mixing (press Play again to re-read the format)"); } return; }
            var fs = f[0], bits = BITS[f[1]], rate = f[2] || 48000;
            if (bits === undefined) return;
            var ch = fs / (bits === 16 ? 2 : 4);
            if (ch < 1 || ch > 8 || ch !== Math.floor(ch)) return;
            var frames = Math.floor(Math.min(n, this.bytes) / fs);
            var desc = rate + "Hz/" + ch + "ch/" + (bits || "float");
            if (S.fmt !== desc) { S.fmt = desc; say("mic stream: " + desc); }
            mix(this.buf, frames, ch, bits, rate);
        } catch (e) { S.pcm = null; S.err = String(e); say("mix failed: " + e); }
    },
});

rpc.exports = {
    onFridaStdin: function (line) {
        line = line.trim();
        if (!line) return;
        var m;
        try { m = JSON.parse(line); } catch (e) { say("bad command: " + line.slice(0, 60)); return; }
        switch (m.cmd) {
            case "fmt": S.sizes = m.sizes || {}; say("formats " + JSON.stringify(S.sizes)); break;
            case "load": S.load = new Float32Array(Math.max(1024, m.samples | 0)); S.loadLen = 0; S.loadRate = m.rate | 0 || 24000; break;
            case "chunk": chunk(String(m.b64 || "")); break;
            case "play":
                if (S.load) { S.pcm = S.load.subarray(0, S.loadLen); S.srcRate = S.loadRate; S.load = null; }
                if (!S.pcm) { say("nothing loaded"); break; }
                S.cur = 0;
                say("playing " + (S.pcm.length / S.srcRate).toFixed(1) + " s" + (Date.now() - S.lastRead > 1000 ? " (no app is recording the mic right now: it plays once one does)" : ""));
                break;
            case "stop": S.pcm = null; S.load = null; say("stopped"); break;
            case "set":
                if (m.vol !== undefined) S.vol = +m.vol;
                if (m.mute !== undefined) S.mute = !!m.mute;
                if (m.loop !== undefined) S.loop = !!m.loop;
                break;
            case "status":
                say("status reads=" + S.reads + " mixed=" + S.mixed + " recording=" + (Date.now() - S.lastRead < 1000) + " playing=" + !!S.pcm + " stream=" + S.fmt + (S.err ? " err=" + S.err : ""));
                break;
        }
    },
};
say("ready (" + (hal ? hal.where : "no hook") + ")");
