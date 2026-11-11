precision mediump float;
varying vec2 vV;
varying float vA;
uniform vec3 uColor;
uniform float uAlpha, uTail, uBright, uSoft;
void main() {
  float across = smoothstep(1.0, 1.0 - uSoft, abs(vV.x));
  float tail = exp(-vV.y * uTail) * smoothstep(0.0, 0.05, vV.y);
  gl_FragColor = vec4(uColor * (uAlpha * vA * across * tail * uBright), 1.0);
}
