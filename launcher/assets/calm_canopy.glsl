// Canopy rain: looking straight up through tall trunks to a patch of sky. The trunks radiate from the opening,
// layered leaves frame it, rain falls TOWARD the camera as radial streaks in three depths, light shafts breathe.
// Rotation is under 1 degree a minute. The same code with AUTUMN defined is the autumn canopy.
#ifdef AUTUMN
const float AUT = 1.0;
#else
const float AUT = 0.0;
#endif

float wrapA(float a) { return mod(a + PI, 2.0 * PI) - PI; }

// one depth of rain falling toward the camera: n angular lanes, head accelerates outward, the tail is motion blur
float rainLayer(float r, float th, float n, float spd, float seed, float len, float wid, float dens) {
  float sec = th / (2.0 * PI) * n + 0.5 * n;
  float id = floor(sec), fx = fract(sec) - 0.5;
  float rs = h11(id * 7.31 + seed);
  float sp = spd * (0.75 + 0.5 * h11(id * 1.7 + seed * 3.0));
  float u = fract(rs + uT * sp);
  float rr = 0.06 + 1.15 * u * u * (1.0 + 0.7 * u);
  float L = len * (0.25 + 1.7 * u);
  float along = (rr - r) / L;
  float inside = step(0.0, along) * step(along, 1.0);
  float lat = fx * (2.0 * PI * max(r, 0.02) / n);
  float w = wid * (0.25 + 1.1 * u);
  float across = smoothstep(w, w * 0.15, abs(lat));
  float g = 0.5 + 0.5 * sin(th * 2.0 + uT * 0.11 + 1.7 * sin(th * 5.0 - uT * 0.07 + seed));
  float vis = step(h11(id + seed * 5.0), dens * (0.40 + 1.1 * g));
  float fade = smoothstep(0.0, 0.08, u) * (1.0 - smoothstep(0.92, 1.0, u));
  return inside * across * vis * fade * pow(1.0 - along, 1.6) * (0.25 + 0.75 * u);
}

// leaves as small tilted ellipses in jittered cells; returns coverage, colour through the out parameter
float leafCells(vec2 ql, float dens, float seed, out vec3 col, float lit) {
  vec2 id = floor(ql);
  vec2 f = fract(ql) - 0.5;
  float h = h21(id + seed);
  float h2 = h21(id * 1.37 + seed + 9.0);
  vec2 jit = (vec2(h21(id + seed + 3.0), h21(id + seed + 5.0)) - 0.5) * 0.35;
  f -= jit;
  float a = h2 * 6.2832;
  vec2 ff = vec2(cos(a) * f.x + sin(a) * f.y, -sin(a) * f.x + cos(a) * f.y);
  float e = length(ff * vec2(1.0, 2.6)) / (0.30 + 0.12 * h);
  float cov = (1.0 - smoothstep(0.82, 1.0, e)) * step(h, dens);
  float vein = 0.85 + 0.15 * smoothstep(0.0, 0.05, abs(ff.y));
  vec3 g1 = mix(vec3(0.07, 0.20, 0.07), vec3(0.50, 0.70, 0.18), lit);
  vec3 g2 = mix(vec3(0.06, 0.15, 0.06), vec3(0.34, 0.58, 0.14), lit);
  vec3 summer = mix(g1, g2, h2);
  vec3 a1 = mix(vec3(0.26, 0.09, 0.02), vec3(0.95, 0.50, 0.10), lit);
  vec3 a2 = mix(vec3(0.22, 0.05, 0.03), vec3(0.80, 0.22, 0.10), lit);
  vec3 a3 = mix(vec3(0.26, 0.17, 0.04), vec3(0.98, 0.76, 0.20), lit);
  vec3 autumn = h2 < 0.4 ? a1 : (h2 < 0.7 ? a2 : a3);
  col = mix(summer, autumn, AUT) * vein * (0.8 + 0.4 * h);
  return cov;
}

vec3 scene(vec2 p) {
  vec2 q = p - vec2(0.0, 0.08);
  float zoom = 1.0 + 0.015 * sin(uT * 0.012);
  q /= zoom;
  float rot = uT * 2.9e-4;                       // 1 degree a minute at most
  q = vec2(cos(rot) * q.x - sin(rot) * q.y, sin(rot) * q.x + cos(rot) * q.y);
  float r = length(q);
  float th = atan(q.y, q.x);
  float R = 0.125 + 0.012 * sin(3.0 * th + 1.0) + 0.008 * sin(5.0 * th + 2.0) + 0.004 * sin(9.0 * th);
  float br = 0.5 + 0.5 * sin(uT * 0.35);

  vec3 amb = mix(vec3(0.035, 0.085, 0.055), vec3(0.17, 0.30, 0.14), exp(-r * 2.8));
  amb = mix(amb, mix(vec3(0.07, 0.035, 0.02), vec3(0.34, 0.20, 0.07), exp(-r * 2.8)), AUT);
  vec3 col = amb;

  // sky patch
  float cl = fbm(q * 7.0 + vec2(uT * 0.01, 0.0));
  vec3 sky = mix(vec3(0.70, 0.80, 0.78), vec3(0.92, 0.96, 0.88), cl);
  sky = mix(sky, vec3(0.80, 0.74, 0.62), AUT * 0.55);
  float skyM = 1.0 - smoothstep(R * 0.80, R * 1.08, r);
  col = mix(col, sky, skyM);

  // trunks: nine rays, birch (pale, black dashes) and maple (dark, grooved), thin near the sky, fat near the camera
  float tr = 0.0;
  vec3 trc = vec3(0.0);
  for (int k = 0; k < 7; k++) {
    float fk = float(k);
    float ak = fk * 0.8976 + 0.5 * h11(fk * 3.1) + 0.012 * sin(uT * 0.2 + fk);
    float dA = wrapA(th - ak - 0.025 * sin(r * 3.0 + fk));
    float w = 0.010 + 0.060 * r + 0.010 * h11(fk);
    float lat = dA * r / w;
    float m = smoothstep(1.0, 0.82, abs(lat)) * smoothstep(R * 0.95, R * 1.5, r);
    float shade = 0.25 + 0.75 * sqrt(max(0.0, 1.0 - lat * lat));
    float lit = exp(-r * 1.4);
    vec3 bark;
    if (mod(fk, 2.0) < 1.0) {
      float dash = step(0.72, fract(r * 30.0 + h11(fk) * 9.0)) * step(0.45, vn(vec2(r * 18.0, fk * 4.0 + lat * 2.0)));
      bark = mix(vec3(0.60, 0.60, 0.55), vec3(0.06, 0.06, 0.06), dash * 0.85);
    } else {
      float gr = vn(vec2(lat * 2.5 + fk * 3.0, r * 5.0));
      bark = mix(vec3(0.15, 0.12, 0.10), vec3(0.28, 0.24, 0.20), gr);
    }
    bark *= shade * (0.18 + 0.9 * lit);
    if (m > tr) {
      trc = bark;
      tr = m;
    }
  }
  col = mix(col, trc, tr);

  // leaves: three depths, denser toward the corners and around the opening, swaying a little
  for (int l = 0; l < 3; l++) {
    float fl = float(l);
    float sc = 16.0 + fl * 9.0;
    vec2 ql = q * sc;
    ql += vec2(sin(uT * 0.31 + fl * 2.0 + q.y * 3.0), cos(uT * 0.27 + fl * 1.3 + q.x * 3.0)) * (0.10 + 0.05 * fl);
    float ring = smoothstep(R * 2.4, R * 1.05, r) * 0.6;
    float outer = smoothstep(0.10 + 0.08 * fl, 0.80, r);
    float dens = clamp(0.16 + 0.70 * outer + ring * (1.1 - 0.3 * fl), 0.0, 0.97);
    float lit = clamp(exp(-(r - R) * 2.2) * (1.0 - 0.25 * fl) + 0.1, 0.0, 1.0);
    vec3 lc;
    float cov = leafCells(ql, dens, fl * 17.3, lc, lit);
    col = mix(col, lc, cov * (0.9 - 0.12 * fl));
    vec3 lc2;
    float cv2 = leafCells(ql * 1.7 + 4.0, dens * 0.8, fl * 17.3 + 40.0, lc2, lit);
    col = mix(col, lc2, cv2 * (0.9 - 0.12 * fl));
  }

  // light shafts breathing from the opening
  float shaft = pow(0.5 + 0.5 * sin(th * 9.0 + 0.6 * sin(th * 3.0 + uT * 0.05)), 4.0)
              * exp(-max(r - R, 0.0) * 3.2) * smoothstep(R * 0.8, R * 1.15, r);
  col += mix(vec3(0.60, 0.70, 0.60), vec3(0.80, 0.62, 0.36), AUT) * shaft * (0.06 + 0.10 * br) * (1.0 + 0.5 * uBreath);

  // rain falling toward the camera: three depths with motion blur, in gusts, following the audio-smoothed intensity
  float dens = clamp(uRain, 0.0, 1.0);
  float rn = rainLayer(r, th, 70.0, 0.050, 1.0, 0.05, 0.0016, dens)
           + rainLayer(r, th, 110.0, 0.075, 2.0, 0.08, 0.0022, dens) * 1.0
           + rainLayer(r, th, 150.0, 0.110, 3.0, 0.14, 0.0034, dens) * 1.15;
  col += vec3(0.78, 0.88, 0.90) * rn * 0.55;

  return col;
}
