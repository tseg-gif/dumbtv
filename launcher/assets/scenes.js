// Procedural scenes for milk.html, drawn on a 960x540 canvas instead of a Milkdrop preset:
// cosmos, night, rain, snow, fire, ocean, city, desert, forest Only shapes: no logos, marks or brand art.
// Scenes.set(name, arg) picks one; Scenes.draw(g, t, dt, st) paints a frame. st carries the music:
//   k      beat pulse 0..1 (already shaped by the comfort level)    e     loudness 0..1
//   bass   0..1                                                    mood  0 slow/dark .. 1 fast/bright
//   p      comfort pulse factor (0.5 high, 0.8 normal, 1 vivid)    sp    animation speed
//   beats  beat count of the clock, clock: true while it is sure   pal   cover colors [[r,g,b]..] or null
// Everything is preallocated and bounded; a frame is a few hundred fills at most.
(function () {
  var W = 960, H = 540, TAU = 6.2832, GY = 430, SPAN = 900, PER = 2; // PER: bars between two landmarks
  var name = '', seed = 1, route = null, city = null, pal = null;
  var camC = 0, camOff = 0, wasClock = false, lastBeats = 0, rotC = 0;
  var N = 200, PX = new Float32Array(N), PY = new Float32Array(N), PV = new Float32Array(N),
      PS = new Float32Array(N), PP = new Float32Array(N);
  var RX = new Float32Array(8), RY = new Float32Array(8), RR = new Float32Array(8), RA = new Float32Array(8), ri = 0;

  function rnd(i) { var x = Math.sin(i * 127.1 + seed * 311.7) * 43758.5453; return x - Math.floor(x); }
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function mix(a, b, t) { return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]; }
  function css(c, a) { return 'rgba(' + (c[0] | 0) + ',' + (c[1] | 0) + ',' + (c[2] | 0) + ',' + (a == null ? 1 : a).toFixed(3) + ')'; }
  function tint(c, k) { return pal && pal[0] ? mix(c, pal[0], k) : c; }
  function sky(g, a, b) {
    var gr = g.createLinearGradient(0, 0, 0, H);
    gr.addColorStop(0, css(a)); gr.addColorStop(1, css(b));
    g.globalAlpha = 1; g.fillStyle = gr; g.fillRect(0, 0, W, H);
  }
  function glow(g, x, y, r, c, a) {
    var gr = g.createRadialGradient(x, y, 0, x, y, r);
    gr.addColorStop(0, css(c, a)); gr.addColorStop(1, css(c, 0));
    g.fillStyle = gr; g.fillRect(x - r, y - r, 2 * r, 2 * r);
  }
  function fill(g, c, a) { g.globalAlpha = 1; g.fillStyle = css(c, a); g.strokeStyle = css(c, a); }
  // shapes in local units: x right, y up from the ground point (x, y), scale s
  function poly(g, p, x, y, s) {
    g.beginPath(); g.moveTo(x + p[0] * s, y - p[1] * s);
    for (var i = 2; i < p.length; i += 2) g.lineTo(x + p[i] * s, y - p[i + 1] * s);
    g.closePath(); g.fill();
  }
  function rect(g, x, y, s, a, b, w, h) { g.fillRect(x + a * s, y - (b + h) * s, w * s, h * s); }
  function disc(g, x, y, s, cx, cy, r) { g.beginPath(); g.arc(x + cx * s, y - cy * s, r * s, 0, TAU); g.fill(); }
  function dome(g, x, y, s, cx, cy, r) { g.beginPath(); g.arc(x + cx * s, y - cy * s, r * s, Math.PI, 0); g.fill(); }
  function ring(g, x, y, s, cx, cy, r, w) {
    g.lineWidth = w * s; g.beginPath(); g.arc(x + cx * s, y - cy * s, r * s, 0, TAU); g.stroke();
  }
  function line(g, x, y, s, x1, y1, x2, y2, w) {
    g.lineWidth = w * s; g.beginPath(); g.moveTo(x + x1 * s, y - y1 * s); g.lineTo(x + x2 * s, y - y2 * s); g.stroke();
  }
  function palm(g, x, y, s, lean) {
    poly(g, [-4, 0, 4, 0, 3 + lean, 150, -3 + lean, 150], x, y, s);
    for (var i = 0; i < 7; i++) {
      var a = -2.7 + i * 0.75, fx = Math.cos(a) * 62, fy = Math.sin(a) * 34 - 6;
      g.lineWidth = 5 * s; g.beginPath(); g.moveTo(x + lean * s, y - 150 * s);
      g.quadraticCurveTo(x + (lean + fx * 0.6) * s, y - (150 + fy + 22) * s, x + (lean + fx) * s, y - (150 + fy - 14) * s);
      g.stroke();
    }
  }
  function stars(g, t, count, a0) {
    for (var i = 0; i < count; i++) {
      var tw = 0.5 + 0.5 * Math.sin(t * 0.0012 * (0.6 + rnd(i + 9)) + rnd(i) * 6.28);
      g.fillStyle = 'rgba(235,235,255,' + (a0 * (0.3 + 0.7 * tw)).toFixed(3) + ')';
      g.fillRect(rnd(i + 3) * W, rnd(i + 5) * H * 0.62, 1 + rnd(i + 7) * 1.6, 1 + rnd(i + 7) * 1.6);
    }
  }
  // the letter moves and looks (see SCENES.letter)
  // finish: body light, mid, dark, stamp color, edge, highlight, backdrop inner, outer, glow rgb, outline-only
  var FIN = [
    ['#f8e686', '#d9ab2c', '#8a6414', 'rgba(18,78,30,0.5)', '#0b3318', 'rgba(255,240,170,0.8)', '#0e3d1f', '#021008', [255, 210, 80], 0],
    ['#f4f7fb', '#b9c3d0', '#6c7a8a', 'rgba(20,90,50,0.45)', '#10222c', 'rgba(255,255,255,0.85)', '#16303a', '#04090c', [230, 235, 250], 0],
    ['#3fd18a', '#12915a', '#075a38', 'rgba(250,214,100,0.65)', '#04261a', 'rgba(250,224,130,0.85)', '#0a3b2c', '#010d09', [120, 230, 170], 0],
    ['#ffd2c4', '#e39a86', '#9a5446', 'rgba(110,40,50,0.4)', '#3a1418', 'rgba(255,230,220,0.85)', '#3a1a2a', '#0c0409', [255, 170, 160], 0],
    ['#2a2a30', '#15151a', '#08080b', 'rgba(240,200,80,0.55)', '#f0c850', 'rgba(255,236,150,0.9)', '#1a1608', '#040302', [255, 200, 60], 0],
    ['#e8fbff', '#8fd8ee', '#3d8fb0', 'rgba(255,255,255,0.5)', '#0c3550', 'rgba(255,255,255,0.95)', '#0c3048', '#020a12', [150, 225, 255], 0],
    ['#0a0a0a', '#050505', '#000000', 'rgba(60,255,120,0.35)', '#46ff7a', 'rgba(190,255,205,0.95)', '#03140a', '#010503', [60, 255, 120], 1],
    ['#f0b080', '#c0703a', '#6a3416', 'rgba(60,24,8,0.45)', '#2a1006', 'rgba(255,214,170,0.85)', '#3a1c0a', '#0c0502', [255, 150, 70], 0]];
  var FONTS = ['900 470px serif', '900 450px sans-serif', 'italic 900 460px serif'], STAMPS = ['$', '$$', '100', '¢'];
  var LCV = null, LCH = 'B', fin = 0, mode = 0, modeT = 0, modeBeat = 0, hold = 3, lbeats = 0, MODES = 10,
      lastBeatT = 0, prevBeatT = -600, flipA = 0, zi = 0, ci = 0;
  var ZS = new Float32Array(8), ZA = new Float32Array(8), CX = new Float32Array(24), CY = new Float32Array(24),
      CVX = new Float32Array(24), CVY = new Float32Array(24), CL = new Float32Array(24);
  function put(g, cv, x, y, sc, rot, a, sx, sy) {
    g.save(); g.globalAlpha = a; g.translate(x, y); g.rotate(rot); g.scale(sc * sx, sc * (sy || 1)); g.drawImage(cv, -280, -280); g.restore(); g.globalAlpha = 1;
  }
  // stamps the letter in a finish, font and pattern onto the one shared canvas
  function stamp(f, font, pat) {
    if (typeof document === 'undefined') return;
    if (!LCV) { LCV = document.createElement('canvas'); LCV.width = 560; LCV.height = 560; }
    var F = FIN[f], c = LCV.getContext('2d'), ch = LCH;
    c.clearRect(0, 0, 560, 560); c.globalCompositeOperation = 'source-over'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.font = FONTS[font];
    if (F[9]) { c.fillStyle = 'rgba(0,0,0,0.55)'; c.fillText(ch, 280, 300); }
    else {
      var fg = c.createLinearGradient(0, 70, 0, 520); fg.addColorStop(0, F[0]); fg.addColorStop(0.5, F[1]); fg.addColorStop(1, F[2]);
      c.fillStyle = fg; c.fillText(ch, 280, 300);
    }
    c.globalCompositeOperation = 'source-atop'; c.fillStyle = F[3]; c.font = 'bold ' + (STAMPS[pat].length > 2 ? 24 : 38) + 'px serif';
    for (var y = 60, r = 0; y < 540; y += 44, r++) for (var x = 20 + (r & 1) * 26; x < 560; x += (STAMPS[pat].length > 2 ? 70 : 52)) c.fillText(STAMPS[pat], x, y);
    c.globalCompositeOperation = 'source-over'; c.font = FONTS[font]; c.lineJoin = 'round';
    c.lineWidth = F[9] ? 10 : 14; c.strokeStyle = F[4]; c.strokeText(ch, 280, 300);
    c.lineWidth = 3; c.strokeStyle = F[5]; c.strokeText(ch, 280, 300);
  }
  function nextMode(t, beats) {
    var m, f; do { m = Math.floor(Math.random() * MODES); } while (m === mode);
    do { f = Math.floor(Math.random() * FIN.length); } while (f === fin);
    mode = m; fin = f; modeT = t; modeBeat = beats; hold = 2 + Math.floor(Math.random() * 3);
    stamp(f, Math.floor(Math.random() * FONTS.length), Math.floor(Math.random() * STAMPS.length));
    for (var i = 0; i < 8; i++) ZA[i] = 0;
    for (i = 0; i < 24; i++) CL[i] = 0;
  }
  function ripple(x, y) { ri = (ri + 1) & 7; RX[ri] = x; RY[ri] = y; RR[ri] = 2; RA[ri] = 0.5; }

  // ---- the keyword scenes ----------------------------------------------------------------
  var SCENES = {
    cosmos: function (g, t, dt, st) {
      rotC += dt * 0.00003 * st.sp;
      g.globalAlpha = 1; g.fillStyle = '#02030a'; g.fillRect(0, 0, W, H);
      glow(g, 480, 270, 340, tint([120, 90, 200], 0.25), 0.16 + 0.3 * st.bass * st.p);
      for (var i = 0; i < 280; i++) {
        var arm = i % 3, r = Math.pow(rnd(i + 1), 0.65) * 0.5, a = arm * 2.0944 + r * 7 + (rnd(i + 2) - 0.5) * 0.7 + rotC;
        var tw = 0.5 + 0.5 * Math.sin(t * 0.0015 * (0.6 + rnd(i + 3) * 1.6) + rnd(i + 4) * 6.28);
        g.fillStyle = 'hsla(' + (205 + rnd(i + 5) * 90).toFixed(0) + ',70%,86%,' + Math.min(1, 0.3 + 0.4 * tw + 0.5 * st.k).toFixed(3) + ')';
        g.beginPath(); g.arc(480 + Math.cos(a) * r * 1500, 270 + Math.sin(a) * r * 750, (0.6 + rnd(i + 6) * rnd(i + 7) * 2.4) * (1 + 1.6 * st.k), 0, TAU); g.fill();
      }
    },
    // A giant letter for a title like "B's on the Table". Ten moves take turns every 2-4 bars (every ~12 s
    // when there is no clock): hero, rippling grid, coin flip, orbit, zoom tunnel, table drop, money rain,
    // glitch slices, close-up pan and a wave. Every change also re-rolls the look: one of 8 finishes, one of
    // 3 letter styles and one of 4 stamp patterns. Movements are locked to the beat and scaled by the
    // comfort level (k, amp); bills and coins drift down at a pace set by the loudness.
    letter: function (g, t, dt, st) {
      var i, amp = Math.min(1, st.p * 2);
      if (st.beats !== lbeats) {
        lbeats = st.beats; prevBeatT = lastBeatT; lastBeatT = t;
        if (mode === 4) { zi = (zi + 1) % 8; ZS[zi] = 0.12; ZA[zi] = 0.9; }
        if (mode === 5) for (i = 0; i < 6; i++) { ci = (ci + 1) % 24; CX[ci] = 480 + (Math.random() - 0.5) * 120; CY[ci] = 440; CVX[ci] = (Math.random() - 0.5) * 0.5; CVY[ci] = -0.2 - Math.random() * 0.3; CL[ci] = 1; }
        if (st.clock && st.beats - modeBeat >= 4 * hold) nextMode(t, st.beats);
      }
      if (t - modeT > 12000 + 4000 * hold * (st.clock ? 1 : 0)) nextMode(t, st.beats);
      var per = clamp(lastBeatT - prevBeatT, 280, 1000), tsb = t - lastBeatT, ph = clamp(tsb / per, 0, 1);
      var intro = clamp((t - modeT) / 600, 0, 1), cvs = LCV, F = FIN[fin];
      var gr = g.createRadialGradient(480, 270, 40, 480, 270, 640);
      gr.addColorStop(0, F[6]); gr.addColorStop(1, F[7]); g.fillStyle = gr; g.fillRect(0, 0, W, H);
      var fall = 0.03 + 0.1 * st.e * st.sp;
      for (i = 0; i < 46; i++) {
        var y = (PY[i] * (H + 80) + t * fall * (0.5 + PV[i])) % (H + 80) - 40, x = PX[i] * W + Math.sin(t * 0.0007 + PP[i]) * 24;
        if (i % 3) {
          g.save(); g.translate(x, y); g.rotate(Math.sin(t * 0.0006 + PP[i]) * 0.9);
          g.fillStyle = 'rgba(70,160,90,' + (0.16 + 0.18 * PV[i]).toFixed(3) + ')'; g.fillRect(-26, -12, 52, 24);
          g.fillStyle = 'rgba(220,240,200,' + (0.22 + 0.18 * PV[i]).toFixed(3) + ')'; g.fillRect(-20, -6, 8, 12); g.fillRect(12, -6, 8, 12); g.restore();
        } else {
          g.fillStyle = 'rgba(240,200,80,' + (0.18 + 0.22 * PV[i]).toFixed(3) + ')'; g.beginPath(); g.arc(x, y, 9 + 5 * PV[i], 0, TAU); g.fill();
        }
      }
      glow(g, 480, 280, 300 + 70 * st.k * st.p, F[8], 0.12 + 0.28 * st.bass * st.p);
      if (!cvs) return;
      var e = Math.exp(-tsb / 170) * amp; // a beat's envelope: 1 at the beat, gone in ~0.4 s
      var flip = (st.beats & 1) ? 1 : -1;
      if (mode === 0) { // hero: swell and tilt
        put(g, cvs, 480, 285 - 10 * e, (1 + 0.12 * e) * (0.6 + 0.4 * intro), flip * 0.03 * e, 1, 1);
      } else if (mode === 1) { // grid: a ripple spreads from the middle after each beat
        for (var r = 0; r < 3; r++) for (var c = 0; c < 5; c++) {
          var dd = Math.abs(c - 2) * 55 + Math.abs(r - 1) * 55, we = tsb >= dd ? Math.exp(-(tsb - dd) / 170) * amp : 0;
          put(g, cvs, 480 + (c - 2) * 190, 285 + (r - 1) * 175, 0.3 * (1 + 0.35 * we) * intro, 0.05 * Math.sin(t * 0.001 + c + r), 0.55 + 0.45 * Math.min(1, we + 0.5), 1);
        }
      } else if (mode === 2) { // coin flip: a half turn per beat, with a hop
        flipA += (st.beats * Math.PI - flipA) * (1 - Math.exp(-dt / 110));
        put(g, cvs, 480, 285 - 36 * Math.sin(ph * Math.PI) * amp, 0.9 * intro, 0, 1, Math.cos(flipA));
      } else if (mode === 3) { // orbit: eight small letters around a breathing one
        var base = t * 0.0004 * (0.5 + 1.2 * st.e) * st.sp + st.beats * 0.2;
        for (i = 0; i < 8; i++) {
          var a2 = base + i * TAU / 8, rr = 1 + 0.16 * e;
          put(g, cvs, 480 + Math.cos(a2) * 330 * rr, 285 + Math.sin(a2) * 175 * rr, 0.22 * (1 + 0.3 * e) * intro, a2 * 0.25, 0.8, 1);
        }
        put(g, cvs, 480, 285, 0.62 * (1 + 0.14 * e) * intro, flip * 0.04 * e, 1, 1);
      } else if (mode === 4) { // tunnel: every beat sends a letter flying out of the middle
        for (i = 1; i <= 8; i++) {
          var z = (zi + i) % 8; if (ZA[z] <= 0.01) continue;
          ZS[z] *= Math.exp(dt * 0.0013); ZA[z] -= dt * 0.0013; if (ZS[z] > 2.4) ZA[z] = 0;
          put(g, cvs, 480, 285, ZS[z], 0, clamp(ZA[z], 0, 0.55), 1);
        }
        put(g, cvs, 480, 285, 0.55 * (1 + 0.1 * e) * intro, 0, 1, 1);
      } else if (mode === 5) { // drop: hops on a table on every beat, squashing as it lands, coins scatter
        g.fillStyle = 'rgba(6,40,18,0.95)'; g.fillRect(0, 440, W, 100); g.fillStyle = 'rgba(240,200,80,0.25)'; g.fillRect(0, 440, W, 3);
        var hop = Math.sin(ph * Math.PI), lift = hop * 150 * amp, sq = ph < 0.12 ? 1 - 0.14 * (1 - ph / 0.12) * amp : 1;
        g.fillStyle = 'rgba(0,0,0,' + (0.3 - 0.12 * hop).toFixed(3) + ')'; g.beginPath(); g.ellipse(480, 452, 150 - 50 * hop, 16 - 5 * hop, 0, 0, TAU); g.fill();
        put(g, cvs, 480, 440 - 196 + 196 * (1 - sq) - lift, 0.78 * intro, 0, 1, 1, sq);
        for (i = 0; i < 24; i++) if (CL[i] > 0) {
          CVY[i] += dt * 0.0011; CX[i] += CVX[i] * dt; CY[i] += CVY[i] * dt * 0.5; if (CY[i] > 438) { CY[i] = 438; CVY[i] *= -0.45; CVX[i] *= 0.8; } CL[i] -= dt * 0.0005;
          g.fillStyle = 'rgba(240,200,80,' + clamp(CL[i], 0, 0.8).toFixed(3) + ')'; g.beginPath(); g.arc(CX[i], CY[i], 8, 0, TAU); g.fill();
        }
      } else if (mode === 6) { // rain: a shower of letters, each swelling on the beat
        for (i = 0; i < 14; i++) {
          var rs = 0.16 + 0.16 * PV[i], ry = ((PY[i] * (H + 200) + t * (0.05 + 0.2 * st.e * st.sp) * (0.6 + PV[i])) % (H + 200)) - 100;
          put(g, cvs, PX[i] * W, ry, rs * (1 + 0.3 * e) * intro, Math.sin(t * 0.0008 + PP[i]) * 0.5, 0.8, 1);
        }
        put(g, cvs, 480, 285, 0.5 * (1 + 0.1 * e) * intro, 0, 1, 1);
      } else if (mode === 7) { // glitch: horizontal slices slide apart on the beat and settle
        var sc2 = 0.95 * intro, sh = 560 / 6, off;
        for (i = 0; i < 6; i++) {
          off = ((i & 1) ? 1 : -1) * flip * 90 * e;
          g.drawImage(cvs, 0, i * sh, 560, sh, 480 - 280 * sc2 + off, 285 - 280 * sc2 + i * sh * sc2, 560 * sc2, sh * sc2 + 1);
        }
      } else if (mode === 8) { // close-up: the camera slides over the letter and kicks in on the beat
        put(g, cvs, 480 + Math.sin(t * 0.00035) * 170, 285 + Math.cos(t * 0.0003) * 90, 1.9 * (1 + 0.08 * e) * intro, Math.sin(t * 0.0002) * 0.1, 1, 1);
      } else { // wave: a row of letters, a ripple running along it after each beat
        for (i = 0; i < 7; i++) {
          var wd = i * 50, ww = tsb >= wd ? Math.exp(-(tsb - wd) / 190) * amp : 0;
          put(g, cvs, 480 + (i - 3) * 138, 285 + Math.sin(t * 0.003 + i * 0.8) * 34 * (0.3 + 0.7 * st.e) - 60 * ww, 0.3 * (1 + 0.3 * ww) * intro, Math.sin(t * 0.002 + i) * 0.08, 1, 1);
        }
      }
    },
    night: function (g, t, dt, st) {
      sky(g, tint([4, 6, 20], 0.2), tint([22, 26, 56], 0.25)); stars(g, t, 110, 1);
      glow(g, 720, 150, 150 + 40 * st.k * st.p, [200, 215, 255], 0.3); g.fillStyle = 'rgb(235,238,250)'; g.beginPath(); g.arc(720, 150, 40 * (1 + 0.04 * st.k * st.p), 0, TAU); g.fill();
      for (var l = 0; l < 3; l++) {
        fill(g, mix([18, 22, 44], [0, 0, 0], l * 0.35), 1); g.beginPath(); g.moveTo(0, H);
        for (var x = 0; x <= W; x += 24) g.lineTo(x, 380 + l * 50 - 60 * Math.sin(x * 0.006 + l * 2 + rotC * 0) - 20 * Math.sin(x * 0.017 + l));
        g.lineTo(W, H); g.fill();
      }
    },
    rain: function (g, t, dt, st) {
      sky(g, tint([14, 18, 30], 0.2), tint([40, 50, 68], 0.25));
      fill(g, [22, 28, 42], 0.8); for (var c = 0; c < 6; c++) { g.beginPath(); g.ellipse(c * 190 + 40 + Math.sin(t * 0.0001 + c) * 30, 60 + (c & 1) * 30, 150, 46, 0, 0, TAU); g.fill(); }
      var fall = 0.5 + 0.8 * st.e * st.sp; g.lineWidth = 1.2; g.strokeStyle = 'rgba(190,210,235,' + (0.22 + 0.22 * st.k).toFixed(3) + ')'; g.beginPath();
      for (var i = 0; i < 170; i++) {
        var y = (PY[i] * H + t * 0.5 * fall * (0.7 + PV[i])) % H, x = PX[i] * W - y * 0.18;
        g.moveTo(x, y); g.lineTo(x - 3, y + 12 + 8 * PV[i]);
      }
      g.stroke(); fill(g, [8, 10, 16], 1); g.fillRect(0, 470, W, 70);
      if (st.beats !== lastBeats) { lastBeats = st.beats; ripple(60 + Math.random() * 840, 480 + Math.random() * 50); }
      g.lineWidth = 1.5;
      for (var q = 0; q < 8; q++) if (RA[q] > 0.01) {
        RR[q] += dt * 0.05; RA[q] -= dt * 0.0006; g.strokeStyle = 'rgba(190,210,235,' + RA[q].toFixed(3) + ')'; g.beginPath(); g.ellipse(RX[q], RY[q], RR[q], RR[q] * 0.28, 0, 0, TAU); g.stroke();
      }
    },
    snow: function (g, t, dt, st) {
      sky(g, tint([10, 16, 38], 0.2), tint([44, 62, 100], 0.25)); stars(g, t, 40, 0.7);
      for (var l = 0; l < 2; l++) { fill(g, mix([196, 210, 235], [30, 40, 70], 0.25 + l * 0.3), 1); g.beginPath(); g.moveTo(0, H);
        for (var x = 0; x <= W; x += 24) g.lineTo(x, 400 + l * 50 - 50 * Math.sin(x * 0.007 + l * 3)); g.lineTo(W, H); g.fill(); }
      var fall = 0.12 + 0.35 * st.e * st.sp;
      for (var i = 0; i < 170; i++) {
        var y = (PY[i] * H + t * fall * (0.5 + PV[i])) % H, x = PX[i] * W + Math.sin(t * 0.0008 * (0.5 + PV[i]) + PP[i]) * 22;
        g.fillStyle = 'rgba(240,246,255,' + (0.45 + 0.4 * PV[i]).toFixed(3) + ')'; g.beginPath(); g.arc(x, y, (1 + 2.2 * PV[i]) * (1 + 0.5 * st.k * st.p), 0, TAU); g.fill();
      }
    },
    fire: function (g, t, dt, st) {
      sky(g, tint([12, 4, 6], 0.15), tint([60, 18, 10], 0.15));
      var hgt = 50 + 90 * st.bass * st.p + 130 * st.k * st.p;
      for (var layer = 0; layer < 2; layer++) {
        g.fillStyle = layer ? 'rgba(255,170,40,0.55)' : 'rgba(200,50,20,0.6)'; g.beginPath(); g.moveTo(0, H);
        for (var x = 0; x <= W; x += 16) {
          var f = 0.55 + 0.45 * Math.sin(x * 0.03 + t * (0.004 + layer * 0.002) + layer * 2) * Math.sin(x * 0.011 - t * 0.0017);
          g.lineTo(x, H - (hgt * (layer ? 0.6 : 1)) * f - 20);
        }
        g.lineTo(W, H); g.fill();
      }
      for (var i = 0; i < 160; i++) {
        PY[i] -= dt * 0.06 * (0.4 + PV[i]) * (0.5 + st.e); if (PY[i] < -0.05) { PY[i] = 1.02; PX[i] = Math.random(); }
        var life = clamp(PY[i], 0, 1); g.fillStyle = 'hsla(' + (15 + PV[i] * 35).toFixed(0) + ',100%,60%,' + (life * 0.7).toFixed(3) + ')';
        g.fillRect(PX[i] * W + Math.sin(t * 0.002 + PP[i]) * 18, PY[i] * H, 2 + PV[i] * 2, 2 + PV[i] * 2);
      }
    },
    ocean: function (g, t, dt, st) {
      sky(g, tint([4, 14, 28], 0.2), tint([16, 56, 84], 0.25)); stars(g, t, 50, 0.8); glow(g, 640, 250, 120 + 30 * st.k * st.p, [180, 220, 255], 0.22);
      for (var l = 0; l < 6; l++) {
        var base = 280 + l * 42, amp = (8 + 16 * st.bass * st.p + 20 * st.k * st.p) * (0.5 + l * 0.2);
        g.fillStyle = css(mix([14, 60, 96], [4, 20, 40], l / 5), 0.85); g.beginPath(); g.moveTo(0, H);
        for (var x = 0; x <= W; x += 16) g.lineTo(x, base + Math.sin(x * 0.011 * (1 + l * 0.2) + t * 0.0008 * (1 + l * 0.3) * st.sp + l) * amp);
        g.lineTo(W, H); g.fill();
      }
    },
    city: function (g, t, dt, st) {
      sky(g, tint([8, 6, 24], 0.25), tint([60, 30, 80], 0.25)); stars(g, t, 40, 0.6);
      var lit = 0.35 + 0.4 * st.k * st.p;
      for (var l = 0; l < 3; l++) {
        var off = (rotC * 0 + t * 0.00002 * (l + 1) * (0.3 + st.e)) * 1200 * (l + 1), sz = 70 + l * 20, c0 = Math.floor(off / sz) - 1;
        g.fillStyle = css(mix([40, 24, 70], [0, 0, 0], l * 0.4), 1);
        for (var i = c0; i < c0 + Math.ceil(W / sz) + 2; i++) {
          var bh = 90 + l * 40 + rnd(i + l * 40) * 150, x = i * sz - off; g.fillRect(x, 440 - bh, sz - 6, bh + 100);
          g.fillStyle = css([255, 214, 120], lit * (0.4 + 0.2 * l));
          for (var wy = 440 - bh + 8; wy < 430; wy += 16) for (var wx = 6; wx < sz - 14; wx += 14) if (rnd(i * 17 + wy + wx + l) > 0.5) g.fillRect(x + wx, wy, 5, 7);
          g.fillStyle = css(mix([40, 24, 70], [0, 0, 0], l * 0.4), 1);
        }
      }
      g.fillStyle = '#06050c'; g.fillRect(0, 470, W, 70);
      for (var c = 0; c < 10; c++) { var cx = ((PX[c] * W * 2 + t * 0.12 * (0.4 + PV[c]) * (0.4 + st.e)) % (W + 200)) - 100; g.fillStyle = c & 1 ? 'rgba(255,90,80,0.55)' : 'rgba(255,240,200,0.55)'; g.fillRect(cx, 490 + (c % 3) * 12, 26, 3); }
    },
    desert: function (g, t, dt, st) {
      sky(g, tint([30, 20, 70], 0.2), tint([255, 140, 70], 0.25)); stars(g, t, 36, 0.5);
      glow(g, 480, 360, 200 + 40 * st.k * st.p, [255, 190, 100], 0.5); g.fillStyle = 'rgb(255,200,120)'; g.beginPath(); g.arc(480, 360, 64 * (1 + 0.03 * st.k * st.p), 0, TAU); g.fill();
      var off = t * 0.01 * (0.3 + st.e) * st.sp;
      for (var l = 0; l < 4; l++) {
        g.fillStyle = css(mix([150, 70, 70], [30, 14, 30], l / 3), 1); g.beginPath(); g.moveTo(0, H);
        for (var x = 0; x <= W; x += 20) g.lineTo(x, 370 + l * 42 - 40 * Math.sin((x + off * (l + 1)) * 0.006 + l * 2) - 10 * Math.sin((x + off * (l + 1)) * 0.02));
        g.lineTo(W, H); g.fill();
      }
      g.fillStyle = '#120912';
      for (var cI = 0; cI < 4; cI++) { var cx = (((cI * 330 - off * 4.2) % 1320) + 1320) % 1320 - 180, hc = 80 + cI * 14; g.fillRect(cx, 500 - hc, 12, hc); g.fillRect(cx - 14, 500 - hc * 0.7, 12, 8); g.fillRect(cx - 14, 500 - hc * 0.7 - 24, 8, 26); g.fillRect(cx + 12, 500 - hc * 0.55, 12, 8); g.fillRect(cx + 17, 500 - hc * 0.55 - 26, 8, 28); }
    },
    forest: function (g, t, dt, st) {
      sky(g, tint([4, 12, 16], 0.2), tint([18, 44, 44], 0.25)); glow(g, 300, 150, 140 + 30 * st.k * st.p, [200, 230, 220], 0.2);
      for (var l = 0; l < 3; l++) {
        var off = t * 0.006 * (l + 1) * (0.3 + st.e) * st.sp, sz = 60 + l * 26, c0 = Math.floor(off / sz) - 1; g.fillStyle = css(mix([14, 40, 36], [0, 4, 4], l / 2), 1);
        for (var i = c0; i < c0 + Math.ceil(W / sz) + 2; i++) {
          var h = 120 + l * 40 + rnd(i + l * 33) * 120, x = i * sz - off + sz / 2;
          for (var tier = 0; tier < 4; tier++) { var ty = 470 - h * (0.18 + tier * 0.22), w = sz * (0.8 - tier * 0.16); g.beginPath(); g.moveTo(x - w, ty + 38); g.lineTo(x, ty - 40); g.lineTo(x + w, ty + 38); g.fill(); }
        }
      }
      g.fillStyle = '#02080a'; g.fillRect(0, 480, W, 60);
      for (var f = 0; f < 60; f++) {
        var b = 0.5 + 0.5 * Math.sin(t * 0.002 * (0.4 + PV[f]) + PP[f] * 6.28), fx = PX[f] * W + Math.sin(t * 0.0005 + PP[f] * 9) * 30, fy = 250 + PY[f] * 230 + Math.cos(t * 0.0006 + PP[f] * 7) * 14;
        g.fillStyle = 'rgba(210,255,150,' + clamp(0.15 + 0.5 * b + 0.4 * st.k, 0, 1).toFixed(3) + ')'; g.beginPath(); g.arc(fx, fy, 1.6 + 1.4 * st.k * st.p, 0, TAU); g.fill();
      }
    }
  };

  // a new song's letter starts with the gold serif; every move change re-rolls the look
  function buildLetter(ch) {
    LCH = ch; fin = 0; mode = 0; modeT = 0; modeBeat = 0; lbeats = 0; lastBeatT = 0; prevBeatT = -600; flipA = 0;
    stamp(0, 0, 0);
  }
  function init() {
    for (var i = 0; i < N; i++) { PX[i] = rnd(i + 1); PY[i] = rnd(i + 2); PV[i] = rnd(i + 3); PS[i] = rnd(i + 4); PP[i] = rnd(i + 5) * 6.28; }
    for (var q = 0; q < 8; q++) RA[q] = 0;
    rotC = 0; lastBeats = 0;
  }
  window.Scenes = {
    names: ['letter', 'cosmos', 'night', 'rain', 'snow', 'fire', 'ocean', 'city', 'desert', 'forest'],
    set: function (n, arg) {
      seed = 1;
      if (arg && n !== 'letter') { var h = 0; for (var i = 0; i < arg.length; i++) h = (h * 31 + arg.charCodeAt(i)) | 0; seed = (h % 997) + 1; }
      if (!SCENES[n]) n = '';
      name = n; init(); if (n === 'letter') buildLetter((arg || 'B').charAt(0).toUpperCase()); else LCV = null;
      return n;
    },
    palette: function (p) { pal = p; },
    draw: function (g, t, dt, st) {
      if (!name) return;
      if (st.pal !== undefined) pal = st.pal;
      g.globalAlpha = 1;
      SCENES[name](g, t, dt, st);
      g.globalAlpha = 1;
    }
  };
})();
