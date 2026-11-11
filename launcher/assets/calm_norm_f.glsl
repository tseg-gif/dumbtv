precision highp float;
varying vec2 vUv;
uniform sampler2D uSim;
float H(vec2 p) { vec4 c = texture2D(uSim, p); return (c.r + c.g / 255.0) * 2.0 - 1.0; }
// the surface slope, smooth and linearly filterable for the water shader
void main() {
  const float e = 1.0 / 256.0;
  vec2 g = vec2(H(vUv + vec2(e, 0.0)) - H(vUv - vec2(e, 0.0)), H(vUv + vec2(0.0, e)) - H(vUv - vec2(0.0, e)));
  gl_FragColor = vec4(0.5 + g * 12.0, 0.5, 1.0);
}
