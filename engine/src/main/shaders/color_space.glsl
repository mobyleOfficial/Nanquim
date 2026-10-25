vec3 srgbToLinear(vec3 encoded) {
  vec3 magnitude = abs(encoded);
  vec3 linear = mix(magnitude / 12.92, pow((magnitude + 0.055) / 1.055, vec3(2.4)),
                    step(vec3(0.04045), magnitude));
  return sign(encoded) * linear;
}

vec3 linearToSrgb(vec3 linear) {
  vec3 magnitude = abs(linear);
  vec3 encoded = mix(magnitude * 12.92, 1.055 * pow(magnitude, vec3(1.0 / 2.4)) - 0.055,
                     step(vec3(0.0031308), magnitude));
  return sign(linear) * encoded;
}

vec4 premultipliedSrgbToLinear(vec4 color) {
  return color.a > 0.0 ? vec4(srgbToLinear(color.rgb / color.a) * color.a, color.a) : vec4(0.0);
}

vec4 premultipliedLinearToSrgb(vec4 color) {
  return color.a > 0.0 ? vec4(linearToSrgb(color.rgb / color.a) * color.a, color.a) : vec4(0.0);
}
