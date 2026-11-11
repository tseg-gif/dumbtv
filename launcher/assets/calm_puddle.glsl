// Night Puddle: low camera over a wet street. Backdrop = night_street photo, or generated crisp street lights.
const float YH = 0.45;

vec3 streetFallback(vec2 uv) {
  float asp = uRes.x / uRes.y;
  vec3 c = mix(vec3(0.07, 0.045, 0.035), vec3(0.012, 0.018, 0.032), smoothstep(0.4, 1.0, uv.y));
  for (int i = 0; i < 12; i++) {
    float fi = float(i);
    vec2 pc = vec2(h11(fi * 3.1 + 0.5), 0.50 + 0.16 * h11(fi * 7.7));
    float rr = 0.0035 + 0.003 * h11(fi * 5.3);   // small and crisp: no soft defocused discs
    float dd = length((uv - pc) * vec2(asp, 1.0));
    vec3 lc = mix(vec3(1.0, 0.60, 0.26), vec3(1.0, 0.86, 0.56), h11(fi * 9.1));
    c += lc * smoothstep(rr, rr * 0.6, dd) * 0.8 * (0.7 + 0.3 * sin(uT * 0.4 + fi));
  }
  float edge = 0.68 + 0.22 * fbm(vec2(uv.x * 6.0, 3.0));
  float canopy = smoothstep(edge - 0.012, edge + 0.012, uv.y);
  float trunks = 0.0;
  for (int i = 0; i < 3; i++) {
    float fi = float(i);
    float tx = 0.16 + 0.34 * fi + 0.06 * h11(fi + 4.0);
    trunks = max(trunks, smoothstep(0.014, 0.007, abs(uv.x - tx - 0.015 * (uv.y - 0.5))) * step(uv.y, 0.86));
  }
  return mix(c, vec3(0.004, 0.007, 0.009), max(canopy, trunks) * 0.93);
}

vec3 backdrop(vec2 uv, float blur) {
#ifdef PHOTO
  return photo(uv, blur) * vec3(0.88, 0.92, 1.0) * 0.85;
#else
  return streetFallback(camUv(uv));
#endif
}

vec3 scene(vec2 p) {
  vec2 uv = vUv;
  vec3 c = uv.y > YH ? backdrop(uv, 0.0) : waterCol(uv, YH, 0.5, 1.0);
  c += vec3(0.06, 0.04, 0.03) * exp(-abs(uv.y - YH) * 28.0) * 0.6;
  c *= 1.0 + 0.06 * uBreath;
  c += vec3(0.4, 0.3, 0.2) * uThunder * 0.05 * smoothstep(0.3, 0.9, uv.y);
  return c;
}
