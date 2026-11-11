// Green Avenue: tree-lined wet road. Backdrop = green_avenue; the lower third is wet road, mirrored and rippled.
const float YH = 0.34;
vec3 backdrop(vec2 uv, float blur) { return photo(uv, blur) * vec3(0.96, 1.0, 0.95); }
vec3 scene(vec2 p) {
  vec2 uv = vUv;
  vec3 c = uv.y > YH ? backdrop(uv, 0.0) : waterCol(uv, YH, 0.35, 0.9);
  c += max(srcB(camUv(uv)) - 0.40, vec3(0.0)) * 0.8; // soft bloom on the lights
  c += vec3(0.04, 0.05, 0.04) * exp(-abs(uv.y - YH) * 22.0) * 0.5;
  c *= 1.0 + 0.06 * uBreath;
  c += vec3(0.3, 0.4, 0.3) * uThunder * 0.05 * smoothstep(0.3, 0.9, uv.y);
  return c;
}
