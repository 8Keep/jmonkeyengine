# Bedrock height v1 provenance

Generated with the built-in image generation tool using our own generated
`bedrock-albedo-v1.png` as the sole structural reference/edit target. No external
images, private material or third-party assets were supplied. The selected tool
output is retained byte-for-byte; the renderer samples its raw red channel as
linear artist-authored height. Dark fractures are recesses. This is not a
calibrated scan, a physically measured material or geometry displacement.

The output approximately registers fractures with the original albedo; exact
registration, perfect grayscale neutrality and seamless tiling are not guaranteed.
Mirrored-repeat sampling provides value continuity, not derivative continuity at
repeat boundaries. Height scale is an explicit artistic parameter in metres.

- File: `bedrock-height-v1.png`
- Dimensions: 1254 × 1254 pixels, opaque RGB8.
- File size: 3808521 bytes.
- SHA-256: `de49ce1ff37190cedd68b370d80e767ca5135204db95f279f64bf012c1db74cb`

## Original prompt

```text
Use case: precise-object-edit
Asset type: original scalar HEIGHT texture for a public open-source terrain renderer example.
Input image 1 is our own generated bedrock albedo image, used as the edit target and structural reference. Convert this same exact rock surface into a registered grayscale height map: preserve the positions and shapes of its existing major fractures, slabs and fine stone grain. Depict slightly uneven broad stone plates at mid grey with softly rounded weathered rims, fine restrained granular height, and the existing thin fractures as narrow dark recessed crevices. Mineral color veins must not automatically become raised white ridges; infer subtle physical stone relief rather than grayscale albedo.
Composition: exact straight-down square edge-to-edge mapping aligned to the input; no change of layout or perspective, no new boulders or objects.
Data encoding: pure neutral grayscale only, black lower and white higher, mostly moderate mid tones; scalar height, NOT a normal map and NOT a photograph of lighting. Smooth continuous plate height with fine restrained variation; dark cracks should be relatively narrow and not huge black pits. No baked directional light, shadows, highlights, ambient occlusion, text, borders or watermarks. One opaque square image, same approximately1254x1254 layout. Keep fine usable surface detail without checkerboard noise. This is artist-authored relief, no claim of calibrated physical measurements.
```
