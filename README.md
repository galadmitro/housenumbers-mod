# House Numbers — NeoForge 1.21.1 mod

Assigns every villager bed ("house") a permanent, unique number, discovered
in order (1, 2, 3, ...), never reused. Villagers already only use the one
bed they've claimed — that's vanilla behavior, this mod just labels it.

## ⚠️ One missing piece, and why

I generated all the mod's actual code and Gradle config, but I could **not**
generate the binary `gradle-wrapper.jar` file (Claude has no network access,
and that file is a compiled binary, not text). Without it, `./gradlew` won't
run as-is. Two ways to fix it — pick whichever is easier for you:

**Option A (zero local setup, recommended for low disk space):**
1. Create a new GitHub repo and push this whole folder to it.
2. On GitHub.com, go to your repo → **Actions** tab → run the "Build Mod"
   workflow (it also auto-runs on every push to `main`).
   GitHub's own servers do the compiling — not your disk.
   `gradlew`'s missing jar isn't a problem here because the workflow can
   auto-generate it (see step 3 below), or you can add it once via GitHub's
   web editor from any official NeoForge MDK template repo (just copy their
   `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, and `gradlew.bat` — e.g.
   from github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle).
3. Download the finished `.jar` from the workflow's "Artifacts" section when
   it finishes (a few minutes).

**Option B (build locally):**
1. Install a JDK 21 (e.g. Temurin) and Gradle.
2. In this folder, run: `gradle wrapper --gradle-version 8.8` — this
   generates the missing `gradlew`, `gradlew.bat`, and wrapper jar for you.
3. Then run `./gradlew build` (Linux/Mac) or `gradlew.bat build` (Windows).
   The finished mod jar appears in `build/libs/`.
   Expect this to download several GB the first time (Minecraft's
   decompiled sources + mappings) — that part can't be avoided for any
   NeoForge mod, mine or otherwise. It's a one-time cost per machine.

## What the mod actually does

- `HouseNumbersMod.java` — the mod's entry point.
- `HouseNumberData.java` — a small save-file (stored inside your world's
  save folder) that remembers which bed already has a number, and what the
  next free number is. This is what guarantees numbers never repeat, even
  across server restarts.
- `VillageEventHandler.java` — every ~5 seconds, scans a 32-block radius
  around each online player for unclaimed/claimed villager "home" beds. Any
  bed it hasn't seen before gets the next number and an invisible, floating
  name tag ("House #7") hovering above it.

## Why villagers already "know their place"

Since Minecraft 1.14, villagers use a Point-of-Interest (POI) system: each
villager claims exactly one bed as its home, and the game itself refuses to
let a second villager claim an already-claimed bed. So as long as each
house has one bed, you already get exactly one villager per house — for
free, with no extra code needed. This mod doesn't change that behavior; it
just visualizes it by numbering the beds.

## Recent changes (untested by Claude - build and report errors back)

- House number labels are now centered on the true midpoint of the bed (beds are
  two blocks, head+foot - previously the label sat over just one half).
- Every adult villager now carries its own small "House #N" tag (rides on top of
  it, distinct from its name tag).
- Villagers now eagerly claim and lock a home bed as soon as they're seen, instead
  of relying on vanilla's slower/competitive claiming - this is meant to stop the
  "runs to a random house when the bell rings" behavior, which usually happens
  because a villager hasn't successfully claimed a bed yet.
- Baby villagers no longer keep a bed of their own: any home they claim gets
  released immediately. Instead, each baby remembers one nearby adult as its
  "parent" (first villager seen nearby, remembered permanently via NBT) and
  paths toward them if it wanders more than ~3 blocks away. The baby's house tag
  mirrors its parent's current house number.

These changes use PoiManager/Brain APIs that are sensitive to exact Minecraft
version - built against 1.21.1 mappings from memory, not compiled/tested locally.
If the build fails, copy the compiler error back and it can be fixed quickly.

## Limitations / things to be aware of

- A "house" here = a bed's POI marker, not the actual building geometry.
  Minecraft doesn't expose building boundaries at runtime, only the POI
  positions villagers care about — which is also exactly what governs
  villager AI, so it's the correct anchor to use.
- Numbers are per-world-save (in `world/data/housenumbers_data.dat`),
  not tied to any specific village — if you have multiple villages, beds
  across all of them get numbered from the same sequence.
- The floating number uses an invisible marker armor stand's name tag. If
  you'd rather it render as an in-world hologram (a real Text Display
  entity) or show a scoreboard/sign instead, that's a straightforward swap
  in `spawnLabel()` — happy to adjust if that's more what you had in mind.
