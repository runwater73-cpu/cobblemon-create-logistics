# Third-party notices

The source code in this repository is licensed under the MIT License in
`LICENSE`.

The following runtime assets have separate terms and are not relicensed under
the project MIT License:

- `src/main/resources/assets/cobblemon_create_logistics/textures/block/magnemite_native.png`
  is derived from Cobblemon's Magnemite asset at
  `assets/cobblemon/textures/pokemon/0081_magnemite/magnemite.png`. It is
  distributed under the asset license shipped by Cobblemon. The complete
  embedded license text is in
  `third-party/Cobblemon-Magnemite-license.txt`.
- The Magnemite geometry in the Hub item model and the
  `models/block/hub/magnemite_*.json` runtime parts is adapted from Cobblemon's
  `0081_magnemite/magnemite.geo.json`. It uses the same separate asset terms.
  The adaptation splits the model into renderable parts, repositions it above
  the Hub base, and converts UV coordinates for Minecraft block models.
- The model files reference Create textures such as
  `create:block/brass_block`, `create:block/brass_casing`, and Create's native
  package item models at runtime. Create remains a required dependency; its
  assets are not included or relicensed here.

Cobblemon, Create, Minecraft, Pokemon, and related names and marks belong to
their respective owners. This project is an independent add-on and is not
endorsed by those projects or rights holders.
