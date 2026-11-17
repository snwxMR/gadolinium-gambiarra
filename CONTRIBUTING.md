# Contributing

Thanks for taking a look. Bug reports, crash reports and results from other GPUs help the most. Use the issue templates.

## The rules the code follows
- **Mixins that coexist.** `@Inject`, `@WrapOperation` and `@WrapMethod` only; no `@Overwrite` and no `@Redirect`, so other mods
  can patch the same methods. No mod jar is ever modified or redistributed.
- **A code lock for every optimization.** Each one checks a SHA-256 fingerprint of the exact methods it depends on, and turns
  itself off (with a warning) when the code there is different. Add the new fingerprint to the build check `checkLocks`.
- **One option each.** Every optimization has its own switch. Anything not yet proven in a real game ships off by default, marked
  experimental.
- **Same result.** An optimization must not change gameplay. Where it can be compared, validation mode (`validationMode=true`) runs
  it next to the original and counts the differences.
- **Licenses.** Gadolinium is LGPL-3.0 and Gambiarra is MIT. Do not copy code from a project whose license does not allow it
  in the mod you are changing, and never commit third-party jars or decompiled code. Credit what you adapt in `NOTICE` and in the
  README. Sodium 0.6 and later (Polyform Shield) are not read.
- Every source file starts with an `SPDX-License-Identifier` line. Code, comments, config texts and logs are in English.

## Layout
- `src/main/java`: both mods. Gadolinium is `nthshaders` and `nthgpu` (the package names come from its old name, Nothirium Shaders); Gambiarra is `gambiarra`, `nthcompat` and `nthrc`.
  `nthshaders.common` and `gambiarra.common` are two copies of the same base: change both (`checkCommonCopies` checks them).
- `src/main/resources`: Gadolinium's resources (mixin configs, compute shaders, licenses). `src/gambiarra/resources`:
  Gambiarra's `mcmod.info`, `LICENSE` and `NOTICE`.
- `src/release` and `src/dev`: the test hooks switch (`DevHooks.ON`), off in release jars.
- `src/buildcheck`: build checks run before packaging (`checkShaders`, `checkLocks`, `checkLod`), with `ferramentas/vanilla`.
- `src/dist`: the README that goes into each package.

## Building
You need JDK 25 and, on your machine, the jars the mods compile against (they are only read, never copied):

1. The target mods, in one folder: the exact files named in `gradle.properties` (Nothirium, RenderLib, Recurrent Complex Volts,
   IvToolkit, Pulsar, RTG Continued, Additional Structures, Biomes O' Plenty, Xaero's World Map, Ice and Fire, LLibrary, Lycanites
   Mobs).
2. The official OptiFine 1.12.2 HD_U_G5 jar (checked by SHA-256). It is remapped locally to SRG names and never leaves your
   machine.
3. For `checkLocks`: Cleanroom's relauncher libraries folder (with the `0.7.14-alpha` universal jar) and the Minecraft 1.12.2 server
   jar from the Unimined cache (the client jar next to it is read too).

Set the paths in `~/.gradle/gradle.properties` (it overrides the project's file, so nothing personal goes into git):

```properties
target_mods_dir=/path/to/instance/mods
optifine_jar_path=/path/to/OptiFine_1.12.2_HD_U_G5.jar
cleanroom_libraries_dir=/path/to/.cleanroom/relauncher/libraries
minecraft_server_jar=/path/to/.gradle/caches/unimined/net/minecraft/minecraft/1.12.2/minecraft-1.12.2-server.jar
```

Then:

```bash
./gradlew buildOptiRefine      # once: builds both accepted OptiRefine builds (78ff8ce and v0.1.0-alpha) into .deps/mods
./gradlew build                # both release jars, in build/libs
./gradlew checkShaders checkLocks checkLod
```

`./gradlew distTeste distGambiarra` makes the packages in `dist/teste/`. It only accepts a new version with an entry in the mod's
changelog and a clean working tree, and it records the version with a local tag.

## Pull requests
Keep them small, one optimization or fix each, with its option, its code lock and, where possible, a validation comparison.
Say how you tested it (the pack, the place, what you compared).
