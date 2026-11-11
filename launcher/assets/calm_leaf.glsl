// Leaf Downpour: macro of wet leaves. The picture sways a hair (about 0.5% warp) as if in wind.
vec3 scene(vec2 p) {
  vec2 uv = vUv;
  vec2 sw = vec2(vn(uv * 2.5 + vec2(uT * 0.04, 0.0)), vn(uv * 2.5 + vec2(5.0, uT * 0.04 + 3.0))) - 0.5;
  uv += sw * 0.010;
  vec3 c = photo(uv, 0.06 + 0.05 * sin(uT * 0.07)) * vec3(0.96, 1.02, 0.93);
  c *= 1.0 + 0.07 * uBreath;
  c += vec3(0.4, 0.5, 0.3) * uThunder * 0.06 * smoothstep(0.2, 0.9, vUv.y);
  return c;
}
