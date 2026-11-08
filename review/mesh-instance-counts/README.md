# Mesh-owned instance statistics

Engine-only OpenGL framebuffer captures, 800 × 600. Both scenes render four
triangle instances from one mesh submission. Camera, geometry and shader inputs
are unchanged; the HUD reads the real renderer statistics after the scene pass.
GUI rendering is excluded from those counters.

| Counter | Before | After |
| --- | ---: | ---: |
| Configured instances | 4 | 4 |
| Vertices | 3 | 12 |
| Triangles | 1 | 4 |
| Objects | 1 | 1 |

Before: upstream `30eb387496c4374d435f86c109a8ad08e00093a8`.
After: fix `fccec17d4be5f2e531144cde1e18d1941c536baf`.

These are application/framebuffer captures with a small engine-only scene, not
mocked statistics, edited screenshots or a performance measurement. No game
assets or private material are included.
