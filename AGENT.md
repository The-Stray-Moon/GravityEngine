# GravityEngine Development And Migration Rules

PROJECT:
    GravityEngine

ARCHITECTURE:
    common/
        Loader-neutral Java 21 kernel and API.

    targets/<loader>-<minecraft-version>/
        Platform adapters and loader/Minecraft integration.

COMMON RULES:
    - Java 21.
    - JOML is explicitly permitted.
    - No Minecraft imports.
    - No Forge imports.
    - No NeoForge imports.
    - No Fabric imports.
    - No Mixin imports.
    - No client/render classes.
    - No platform packet/registry implementation.

IDENTITY:
    Java package = cc.sighs.gravityengine
    mod id = gravityengine
    mod name = GravityEngine

IMPORTANT:
    StarminerR is a separate future content mod that consumes GravityEngine.
    Do not use `starminerr` as GravityEngine's canonical mod, resource,
    package, class, system-property, or internal bridge identity.

TARGET STATUS:
    neoforge-1.21.1 is the authoritative migrated target.
    fabric-1.20.1, forge-1.20.1, and neoforge-26.1 are current
    scaffolding/ports and are not yet complete implementations of the
    GravityEngine runtime.

BOUNDARY RULES:
    - Keep common loader-neutral. Do not move Minecraft-dependent code into
      common merely to share it.
    - Keep Minecraft, loader, Mixin, networking, rendering, and metadata
      integration inside the appropriate target.
    - Do not duplicate classes between common and a target.
    - Preserve a single coherent GravityEngine identity across root Gradle
      metadata, targets, resources, Mixin configuration, and internal names.
