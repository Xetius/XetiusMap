# Minecraft 26.2 API notes

Notes gathered while building this project, verified against the actual 26.2 jar and Fabric API
`0.157.0+26.2`. 26.x moved enough that most pre-26 tutorials and mod sources are misleading, so
these are the things that cost time.

## The game ships unobfuscated

There are no `client_mappings` in the 26.2 version manifest, and Yarn stopped at 1.21.11. The jar
carries real names. Consequences for the build script:

```kotlin
plugins { id("net.fabricmc.fabric-loom") }   // NOT "fabric-loom"

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    // no `mappings` line at all — officialMojangMappings() fails with
    // "Failed to find official mojang mappings for 26.2"
    implementation("net.fabricmc:fabric-loader:0.19.3")       // not modImplementation
    implementation("net.fabricmc.fabric-api:fabric-api:0.157.0+26.2")
}
```

There is no remap step, so `jar` is the final mod artifact. The older remapping flow lives on as the
separate `net.fabricmc.fabric-loom-remap` plugin for 1.21.x.

`./gradlew genSources` still works and writes to
`.gradle/loom-cache/minecraftMaven/.../*-sources.jar` in the project, not the Gradle home.

## Renames that break almost everything

| Before | 26.2 |
|---|---|
| `net.minecraft.resources.ResourceLocation` | `net.minecraft.resources.Identifier` |
| `ResourceKey#location()` | `ResourceKey#identifier()` |
| `GuiGraphics` | `net.minecraft.client.gui.GuiGraphicsExtractor` |
| `Renderable#render(...)` | `Renderable#extractRenderState(GuiGraphicsExtractor, int, int, float)` |
| `Screen#render(...)` | `Screen#extractRenderState(...)` |
| `Minecraft#screen` (field) | `Minecraft#gui.screen()` |
| `Minecraft#setScreen(...)` | `Minecraft#gui.setScreen(...)` |
| `KeyBindingHelper` (`keybinding.v1`) | `KeyMappingHelper` (`keymapping.v1`) |
| `WorldRenderEvents` | `LevelRenderEvents` (`rendering.v1.level`) |
| `ChunkPos.x` / `.z` (fields) | `ChunkPos` is a record: `x()` / `z()` |

`Options.hideGui` is gone. A Fabric HUD element inherits the render condition of whichever vanilla
element it is attached relative to, so F1 hiding comes for free with
`HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, id, element)`.

## Rendering

`GuiGraphicsExtractor` is the drawing surface. The useful entry points here were:

```java
graphics.fill(x0, y0, x1, y1, argb);
graphics.outline(x, y, width, height, argb);
graphics.enableScissor(x0, y0, x1, y1);   // corners, not x/y/w/h
graphics.disableScissor();
graphics.blit(identifier, x0, y0, x1, y1, u0, u1, v0, v1);   // corners + UV range
graphics.text(font, component, x, y, argb);
graphics.centeredText(font, component, centreX, y, argb);
graphics.pose();   // org.joml.Matrix3x2fStack — 2D only
```

Note the two families of `blit`: the `RenderPipeline`-first overloads take `(x, y, u, v, width,
height, textureWidth, textureHeight)`, while the plain `Identifier` overload takes **corners** `(x0,
y0, x1, y1)` and a **normalised UV range**. Mixing them up produces a silently wrong result.

`enableScissor` applies `transformAxisAligned` against the *current* pose, so push the scissor
before applying any rotation or it will clip the wrong rectangle.

26.2 also adds a Vulkan backend alongside OpenGL, with OpenGL slated for removal. Anything doing raw
GL calls instead of going through Blaze3D will break; this project only uses `GuiGraphicsExtractor`
and `DynamicTexture`, so it is unaffected.

### Textures

```java
NativeImage image = new NativeImage(width, height, false);
image.setPixel(x, y, argb);                       // ARGB in, converted to ABGR internally
DynamicTexture texture = new DynamicTexture(() -> "label", image);
Minecraft.getInstance().getTextureManager().register(identifier, texture);
texture.upload();                                  // after mutating the image
```

`DynamicTexture` samples with `FilterMode.NEAREST` and repeat wrapping. Creating one touches
`RenderSystem`, so build the pixel data anywhere but only construct and upload on the render thread.

## Colours

`BlockColors` no longer has `getColor(state, level, pos, tintIndex)`. It is now:

```java
List<BlockTintSource> tints = blockColors.getTintSources(state);
int rgb = tints.getFirst().colorInWorld(state, blockAndTintGetter, pos);
```

`BlockState#getMapColor(BlockGetter, BlockPos)` survives, and `MapColor.Brightness` still exposes
`modifier` (LOW 180, NORMAL 220, HIGH 255, LOWEST 135) alongside
`MapColor#calculateARGBColor(Brightness)`.

## Input

`Screen` and `GuiEventListener` take event records rather than loose primitives:

```java
boolean mouseClicked(MouseButtonEvent event, boolean doubleClick);
boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY);
boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY);
boolean keyPressed(KeyEvent event);           // event.key() for the GLFW code
```

`KeyMapping` needs a `KeyMapping.Category`, registered with
`KeyMapping.Category.register(Identifier)`.

## Networking

Unchanged in shape from 1.21.x, and still ordinary custom payloads underneath, which is what lets a
Fabric client talk to a Bukkit plugin over the same channel id:

```java
PayloadTypeRegistry.serverboundPlay().register(TYPE, CODEC);
PayloadTypeRegistry.clientboundPlay().register(TYPE, CODEC);
ClientPlayNetworking.registerGlobalReceiver(TYPE, (payload, context) -> { ... });
```

Write the payload body as raw bytes with no length prefix — Bukkit's `Messenger` hands a plugin
exactly the bytes after the channel id, so any extra framing shows up as corruption on the server.

## Paper 26.2

Nothing surprising. `api-version: '26.2'` is valid in `plugin.yml`, Java 25 is required, and
`World#getKey()` returns the level's resource key, which matches what the client sees from
`ClientLevel#dimension().identifier()` — that agreement is what lets both sides key map data by the
same dimension string.
