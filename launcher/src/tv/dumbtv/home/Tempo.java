package tv.dumbtv.home;

import java.util.Arrays;

/**
 * Tempo and a beat clock from the music's onsets.
 *
 * Tempo: every callback adds one point to an onset envelope (multiband spectral flux: bass, mids and
 * highs, so snares and hats count and not only kicks). Twice a second the last ~8 s of it are
 * autocorrelated band by band; the candidate lags are scored together with their multiples (a true
 * beat repeats at 1.5, 2, 3 and 4 beats too) and weighted by a prior around 110 BPM, so hip-hop at
 * 90 beats its double time at 180. A new tempo has to win three evaluations
 * in a row before it replaces the old one.
 *
 * Clock: a phase-locked loop. The period comes from the autocorrelation; every kick near a predicted
 * beat pulls the phase (and, slowly, the period) toward itself, so the clock keeps going through a
 * missed kick. The heaviest of the four beats is the downbeat, so bars (4/4) start there.
 *
 * Confidence (0..1) is the larger of two things: how strongly the envelope repeats at the chosen tempo,
 * and how tightly the last dozen or two detected kicks fit one eighth-note grid. The second is a direct
 * fit of tempo and phase to the kick times (a resultant-length test per candidate tempo, with a mild
 * prior), so it locks sparse, swung hip-hop whose envelope repeats only weakly, and it also corrects
 * half-time and double-time mistakes. A wrong tempo does not fit the kicks, so it stays unconfident. Times are one
 * monotonic millisecond clock of the caller's choice.
 */
final class Tempo {
    private static final int N = 400; // 8 s at the meter's 40 points a second
    private static final long WINDOW_MS = 8000, MIN_MS = 6000, EVERY_MS = 500;
    /** Kicks heard in the last 20 s that count as a drum track, so a slow tempo may be read as every other beat. */
    static int OCTAVE_KICKS = 12;

    private final float[][] env = new float[3][N]; // bass, mid, high
    private final long[] et = new long[N];
    private int head, count;
    private long lastEval;

    private float bpm, conf;
    private double basePeriod = 500, period = 500, freqErr;
    private long anchor, beatBase;
    private float candBpm;
    private int candWins;
    private final float[] pos = new float[4];
    private int downbeat;
    private boolean half;
    private boolean octaveHeld;
    private static final int KN = 48;
    private final long[] kt = new long[KN];
    private final float[] kw = new float[KN];
    private int kHead, kCount;
    private long lastFit;
    private float kickConf, kickBpm;

    synchronized void reset() {
        head = count = 0;
        lastEval = 0;
        bpm = conf = candBpm = 0;
        candWins = 0;
        basePeriod = period = 500;
        freqErr = 0;
        anchor = beatBase = 0;
        Arrays.fill(pos, 0f);
        downbeat = 0;
        kHead = kCount = 0;
        lastFit = 0;
        kickConf = kickBpm = 0;
        octaveHeld = false;
    }

    /** One onset-envelope point per audio callback: positive spectral change in three bands. */
    synchronized void onset(long now, float bass, float mid, float high) {
        env[0][head] = bass;
        env[1][head] = mid;
        env[2][head] = high;
        et[head] = now;
        head = (head + 1) % N;
        if (count < N) count++;
        if (now - lastEval >= EVERY_MS) {
            lastEval = now;
            evaluate(now);
        }
    }

    private int recentKicks(long now) {
        int n = 0;
        for (int k = 0; k < kCount; k++) {
            if (now - kt[(kHead - 1 - k + KN * 2) % KN] > 20000) break;
            n++;
        }
        return n;
    }

    private void evaluate(long now) {
        // the points of the last WINDOW_MS, oldest first
        int n = 0;
        float[][] x = new float[3][count];
        long first = 0, last = 0;
        for (int k = 0; k < count; k++) {
            int i = (head - count + k + N) % N;
            if (now - et[i] > WINDOW_MS) continue;
            if (n == 0) first = et[i];
            last = et[i];
            for (int b = 0; b < 3; b++) x[b][n] = env[b][i];
            n++;
        }
        if (n < 40 || last - first < MIN_MS) {
            conf = 0;
            return;
        }
        float rate = (n - 1) * 1000f / (last - first);
        int lmin = Math.max(3, (int) Math.floor(rate * 60f / 210f)), lmax = (int) Math.ceil(rate * 60f / 50f);
        int top = Math.min(n - 8, 4 * lmax + 2);
        // Each band is correlated on its own and then combined: a dense hi-hat grid repeats at every
        // multiple of half a beat and would drown out the kicks and snares that carry the beat.
        double[] rho = new double[top + 1], rhoBass = new double[top + 1];
        double wsum = 0;
        double[] bandW = {1.0, 0.7, 0.3};
        for (int b = 0; b < 3; b++) {
            // onsets are smeared over a few frames in real audio; smoothing by one frame each way keeps a
            // beat that falls between two integer lags (9.4 frames, say) from correlating badly at both
            float[] sm = new float[n];
            for (int i = 0; i < n; i++) {
                sm[i] = 0.5f * x[b][i] + 0.25f * x[b][Math.max(0, i - 1)] + 0.25f * x[b][Math.min(n - 1, i + 1)];
            }
            System.arraycopy(sm, 0, x[b], 0, n);
            double mean = 0;
            for (int i = 0; i < n; i++) mean += x[b][i];
            mean /= n;
            double r0 = 0;
            for (int i = 0; i < n; i++) {
                x[b][i] -= mean;
                r0 += x[b][i] * x[b][i];
            }
            if (r0 < 1e-4) continue; // this band is silent or flat
            wsum += bandW[b];
            for (int l = 1; l <= top; l++) {
                double sum = 0;
                for (int i = l; i < n; i++) sum += x[b][i] * x[b][i - l];
                double v = sum / r0 * n / (n - l); // unbiased, 1 for a perfect repeat
                rho[l] += bandW[b] * v;
                if (b == 0) rhoBass[l] = v;
            }
        }
        if (wsum == 0) {
            conf = 0;
            return;
        }
        for (int l = 1; l <= top; l++) rho[l] /= wsum;
        double bestScore = -1, bestLag = 0, bestRaw = 0;
        for (int l = lmin + 1; l < lmax && l + 1 <= top; l++) {
            double sc = comb(rho, l, top);
            double a = comb(rho, l - 1, top), c = comb(rho, l + 1, top);
            if (sc < a || sc < c) continue; // not a peak
            double den = a - 2 * sc + c;
            double lf = den == 0 ? l : l + 0.5 * (a - c) / den; // parabolic peak
            float cb = (float) (60.0 * rate / lf);
            double w = prior(cb);
            if (sc * w > bestScore) {
                bestScore = sc * w;
                bestLag = lf;
                bestRaw = sc;
            }
        }
        if (bestLag <= 0) {
            conf = 0;
            return;
        }
        // Double time? If the kicks themselves repeat at half that lag (a kick on every beat while the
        // snares only mark every second beat), the beat is the shorter one. The neighbors of the exact
        // half are tried too: a beat of 9.4 frames repeats best at 9 or 10.
        int half = (int) Math.round(bestLag / 2), bestHalf = -1, full = (int) Math.round(bestLag);
        for (int h = half - 1; h <= half + 1; h++) {
            if (h < lmin + 1 || h >= lmax || h + 1 > top || 60.0 * rate / h > 180) continue;
            if (rhoBass[h] >= 0.45 && rhoBass[h] >= 0.7 * rhoBass[Math.min(top, full)]
                    && (bestHalf < 0 || rhoBass[h] > rhoBass[bestHalf])) bestHalf = h;
        }
        if (bestHalf > 0) bestLag = bestLag / 2;
        // Octave resolver (prefers 80..160 BPM): a tempo under 80 is doubled when the combined onset envelope
        // repeats at half that lag nearly as strongly (a backbeat's kick and snare each mark one real beat, so the
        // slow tempo is only every other beat) and the double stays at or under 165. A ballad whose notes land only
        // on the slow beat has no such support and keeps its tempo.
        if (bestLag > 0 && 60.0 * rate / bestLag < 80.0 && 60.0 * rate / (bestLag / 2) <= 165.0 && recentKicks(now) >= OCTAVE_KICKS) {
            bestLag = bestLag / 2;
            octaveHeld = true;
        }
        // Once doubled, stay doubled through a breakdown or outro: with the drums gone the slow lag wins again, but
        // it is the same song at the same tempo (found with the synthetic rock track: right until the drums stopped).
        if (octaveHeld && bestLag > 0 && bpm >= 80f) {
            for (int k = 2; k <= 4; k *= 2) { // a half or a quarter of the held tempo is a bar or two beats of it
                if (Math.abs(60.0 * rate / bestLag * k - bpm) < bpm * 0.05f) {
                    bestLag /= k;
                    break;
                }
            }
        }
        if (bestLag <= 0) {
            conf = 0;
            return;
        }
        float cand = (float) (60.0 * rate / bestLag);
        float c = (float) Math.max(0, Math.min(1, (bestRaw - 0.03) / 0.16)) * Math.min(1f, (last - first) / 7000f);
        if (bpm == 0) {
            setTempo(cand, now);
            candBpm = 0;
        } else if (Math.abs(cand - bpm) < bpm * 0.05f) {
            setTempo(bpm * 0.8f + cand * 0.2f, now); // the same tempo, refined
            candBpm = 0;
        } else if (candBpm != 0 && Math.abs(cand - candBpm) < candBpm * 0.05f) {
            if (++candWins >= 3) {
                setTempo(cand, now);
                candBpm = 0;
                c *= 0.6f; // just changed: not sure yet
            }
        } else {
            candBpm = cand;
            candWins = 1;
        }
        conf = conf == 0 ? c : conf * 0.5f + c * 0.5f;
    }

    /**
     * The autocorrelation at a lag plus its multiples: a real beat also repeats after 1.5, 2, 3 and 4
     * beats, while a wrong lag (two thirds of the tempo, say) does not repeat at 2.25 beats.
     */
    private static double comb(double[] rho, int l, int top) {
        double s = rho[l], w = 1;
        if (2 * l <= top) {
            s += 0.5 * rho[2 * l];
            w += 0.5;
        }
        if (4 * l <= top) {
            s += 0.25 * rho[4 * l];
            w += 0.25;
        }
        if (3 * l <= top) {
            s += 0.3 * rho[3 * l];
            w += 0.3;
        }
        int h = (3 * l) / 2;
        if (h + 1 <= top) {
            double f = 1.5 * l - h; // linear interpolation at 1.5 * l
            s += 0.4 * (rho[h] * (1 - f) + rho[h + 1] * f);
            w += 0.4;
        }
        return s / w;
    }

    /**
     * The prior: log-normal around 110 BPM (sigma 0.45 in ln units). 128 beats its half time 64, 90
     * beats its double 180, 140 beats 70, and a tempo outside 70..160 needs a clearly stronger
     * repeat to win.
     */
    private static double prior(float b) {
        return Math.exp(-0.5 * sq(Math.log(b / 110.0) / 0.45));
    }

    private static double sq(double v) {
        return v * v;
    }

    static boolean DEBUG_SET;

    private void setTempo(float b, long now) {
        if (DEBUG_SET) System.out.println("    setTempo " + b + " at " + now + " held=" + octaveHeld + " kicks=" + recentKicks(now) + " kickConf=" + kickConf);
        if (anchor != 0) {
            // keep the grid where it is while the period changes: re-anchor on the last beat at or before now.
            // (The nearest beat can lie ahead of now: the clamp in rawBeats then swallowed the next beat and the index
            // jumped up to half a beat early, at the arbitrary moment of this call. Viz Lab measured it: beats fired 170-270 ms early.)
            double k = Math.max(0.0, Math.floor((now - anchor) / period));
            anchor += Math.round(k * period);
            beatBase += (long) k;
        }
        if (bpm != 0 && Math.abs(b - bpm) > bpm * 0.05f) freqErr = 0;
        if (bpm != 0 && b >= 80f) {
            // a tempo that doubles (by the resolver or the bass-repeat rule) is held through a drum-less breakdown;
            // a change to an unrelated tempo lets go
            float r = b / bpm;
            if (Math.abs(r - 2f) < 0.12f) octaveHeld = true;
            else if (Math.abs(r - 1f) > 0.05f && Math.abs(r - 0.5f) > 0.05f) octaveHeld = false;
        }
        bpm = b;
        basePeriod = 60000.0 / b;
        period = basePeriod * (1 + freqErr);
    }

    /** A kick of strength 0.5..1 was heard at {@code now}: the PLL's phase detector, and a point for the kick fit. */
    synchronized void kick(long now, float strength) {
        kt[kHead] = now;
        kw[kHead] = strength;
        kHead = (kHead + 1) % KN;
        if (kCount < KN) kCount++;
        if (now - lastFit >= 400 && kCount >= 10) {
            lastFit = now;
            fitKicks(now);
        }
        if (bpm == 0) return;
        if (anchor == 0 || conf() < 0.3f) {
            if (anchor != 0) beatBase = beats(now) + 1;
            anchor = now;
            return;
        }
        double ph = (now - anchor) / period, e = ph - Math.rint(ph);
        if (Math.abs(e) < 0.3) {
            anchor += Math.round(e * period * 0.15); // phase (Viz Lab: 0.3 moved the beat up to 54 ms in one step, visible as an uneven pulse)
            freqErr = Math.max(-0.03, Math.min(0.03, freqErr + 0.03 * e)); // a late kick: the beat is longer
            period = basePeriod * (1 + freqErr);
            // which of the four beats carries the heaviest kicks is the downbeat
            long idx = beatBase + Math.round((now - anchor) / period);
            for (int i = 0; i < 4; i++) pos[i] *= 0.97f;
            pos[(int) (idx & 3)] += strength;
            int topPos = 0;
            for (int i = 1; i < 4; i++) if (pos[i] > pos[topPos]) topPos = i;
            // switch the downbeat only for a clearly stronger candidate, or bars would jump around
            if (topPos != downbeat && pos[topPos] > pos[downbeat] * 1.25f) downbeat = topPos;
        }
    }

    /**
     * Fits a tempo and phase to the recent kicks. For each candidate tempo the kick times are wrapped onto
     * an eighth-note lattice and the length of their mean vector is the fit (1 = every kick on the grid, about
     * 1/sqrt(n) for random kicks). Octave-equivalent tempos fit equally, so a log-normal prior around 110 BPM
     * breaks the tie. A good fit sets the tempo and puts the grid on the heavier of the two eighth phases
     * (the beat, not the off-beat).
     */
    private void fitKicks(long now) {
        int n = 0;
        long[] t = new long[KN];
        float[] w = new float[KN];
        for (int k = 0; k < kCount; k++) {
            int i = (kHead - 1 - k + KN * 2) % KN;
            if (now - kt[i] > 20000) break;
            t[n] = kt[i];
            w[n] = kw[i];
            n++;
        }
        if (n < 10) {
            kickConf *= 0.8f;
            return;
        }
        double wsum = 0;
        long base = t[n - 1];
        for (int i = 0; i < n; i++) wsum += w[i];
        double bestScore = 0, bestR = 0, bestBpm = 0, bestPsi = 0;
        for (double b = 60; b <= 180; b += 0.5) {
            // kicks on beats 1 and 3 only fit many tempos (5:4, 3:2 ...), so the search stays within a few percent of
            // the repetition-based tempo or its half or double; the kicks settle the octave and the phase
            if (bpm > 0) {
                boolean near = Math.abs(b / bpm - 1) < 0.04 || Math.abs(b / (bpm * 2) - 1) < 0.04 || Math.abs(b / (bpm * 0.5) - 1) < 0.04;
                if (!near) continue;
            }
            double lat = 30000.0 / b; // an eighth note in ms
            double re = 0, im = 0;
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * (t[i] - base) / lat;
                re += w[i] * Math.cos(a);
                im += w[i] * Math.sin(a);
            }
            double r = Math.hypot(re, im) / wsum;
            // octave tie-break: kicks every other beat fit the half tempo as well as the real one, and kick times
            // that jitter by a frame fit the slow lattice a little better, so the 80..165 range gets a bonus
            double sc = r * Math.pow(prior((float) b), 0.5) * (b >= 80 && b <= 165 ? 1.12 : 0.9);
            if (sc > bestScore) {
                bestScore = sc;
                bestR = r;
                bestBpm = b;
                bestPsi = base + Math.atan2(im, re) / (2 * Math.PI) * lat;
            }
        }
        // significance: n*R^2 is about 1 for random kicks and the best of ~50 tempo candidates reaches ~5 by chance
        float c = (float) Math.max(0, Math.min(1, (n * bestR * bestR - 5.5) / 6.0));
        if (c <= 0) {
            kickConf *= 0.8f;
            return;
        }
        kickConf = kickConf == 0 ? c : kickConf * 0.5f + c * 0.5f;
        kickBpm = (float) bestBpm;
        if (kickConf >= 0.5f) {
            if (bpm == 0 || Math.abs(bestBpm - bpm) > bpm * 0.03f) setTempo((float) bestBpm, now);
            // the beat is whichever eighth phase carries the heavier kicks
            double lat = 30000.0 / bestBpm, even = 0, odd = 0;
            for (int i = 0; i < n; i++) {
                long k = Math.round((t[i] - bestPsi) / lat);
                if ((k & 1) == 0) even += w[i];
                else odd += w[i];
            }
            long off = even >= odd ? 0 : 1;
            long kNow = (long) Math.floor((now - bestPsi) / (2 * lat));
            double gridAt = bestPsi + (2 * kNow + off) * lat; // a beat time at or before now
            if (gridAt > now) gridAt -= 2 * lat;
            double d = (gridAt - anchor) / period; // how far the fitted beat is from the PLL's, in beats
            if (anchor == 0 || Math.abs(d - Math.rint(d)) > 0.22) {
                long was = beats(now);
                anchor = Math.round(gridAt);
                beatBase = was - Math.max(0, (long) Math.floor((now - anchor) / period));
            }
        }
    }

    /** Hip-hop feel: with a tracked tempo of 110+ BPM the clock counts half-time (beats, bars and phase). */
    synchronized void setHalfTime(boolean on) {
        half = on;
    }

    private boolean eff() {
        return half && bpm >= 110f;
    }

    /** the tracked tempo before any half-time counting */
    synchronized float rawBpm() {
        return bpm;
    }

    synchronized boolean halfTime() {
        return half;
    }

    synchronized float bpm() {
        return eff() ? bpm / 2f : bpm;
    }

    synchronized float conf() {
        return Math.max(conf, kickConf);
    }

    synchronized double period() {
        return eff() ? period * 2 : period;
    }

    synchronized long anchor() {
        return anchor;
    }

    /** Beats since the clock started, on the predicted grid. */
    synchronized long beats(long now) {
        long r = rawBeats(now);
        return eff() ? r >> 1 : r;
    }

    private long rawBeats(long now) {
        return anchor == 0 ? 0 : beatBase + Math.max(0, (long) Math.floor((now - anchor) / period));
    }

    /** Bars (4/4), counted from the downbeat: the bar number changes on the heaviest beat. */
    synchronized long bars(long now) {
        return Math.floorDiv(rawBeats(now) - downbeat, eff() ? 8L : 4L);
    }

    /** 0..3 position of this moment inside the bar (0 is the downbeat). */
    synchronized int beatInBar(long now) {
        long r = Math.floorMod(rawBeats(now) - downbeat, eff() ? 8L : 4L);
        return (int) (eff() ? r / 2 : r);
    }

    /** 0..1 position inside the current beat. */
    synchronized float phase(long now) {
        if (anchor == 0) return 0f;
        double p = (now - anchor) / period;
        float ph = (float) (p - Math.floor(p));
        return eff() ? (((rawBeats(now) - beatBase) & 1) + ph) / 2f : ph;
    }
}
