#ifdef GL_ES
precision highp float;
#endif
in vec3 v_WorldPosition;
in vec3 v_Normal;
out vec4 fragColor;

vec3 mixColor(vec3 first, vec3 second, float alpha) {
    return mix(first, second, clamp(alpha, 0.0, 1.0));
}

void main() {
    vec3 normal = normalize(v_Normal);
    float steepness = 1.0 - clamp(normal.y, 0.0, 1.0);
    float rock = smoothstep(0.24, 0.68, steepness);
    float snow = smoothstep(6.2, 7.3, v_WorldPosition.y) * (1.0 - smoothstep(0.62, 0.9, steepness));
    vec3 ground = mixColor(vec3(0.12, 0.24, 0.075), vec3(0.34, 0.29, 0.22), rock);
    ground = mixColor(ground, vec3(0.84, 0.87, 0.88), snow);
    vec3 lightDirection = normalize(vec3(-0.42, 0.82, -0.38));
    float diffuse = 0.30 + 0.70 * max(dot(normal, lightDirection), 0.0);
    float heightTint = 0.96 + 0.04 * clamp(v_WorldPosition.y / 8.0, 0.0, 1.0);
    fragColor = vec4(ground * diffuse * heightTint, 1.0);
}
