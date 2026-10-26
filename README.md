# GravityEngine

GravityEngine provides reusable gravity fields, movement/collision kernels,
body-attitude control and Minecraft integration. Content such as StarminerR blocks,
world generation and gameplay policy remains external. The identity is
`cc.sighs.gravityengine` / `gravityengine`.

## Supported baseline

| Component | Runtime | Status |
| --- | --- | --- |
| common | Java 17 | Platform-neutral kernel and consumer API |
| NeoForge 1.21.1 | Java 21, NeoForge 21.1.256 | Primary integration; API baseline 0.0.2 |
| Optional Sable | 2.0.6 on the primary target | Runtime compatibility; historical installed/absent evidence |
| Forge / Fabric 1.20.1 | Java 17 runtime; Fabric build launcher Java 25 | Scaffold; integration parity unverified |
| NeoForge 26.1 | Java 25 | Scaffold; integration parity unverified |

A successful build is not cross-version or client gameplay acceptance.
Live validation uses `scripts/verify-autojs.py` and AutoJS scenarios; retain fresh
results for each run. See [AGENTS.md](AGENTS.md) for development and verification
requirements.

## Build and use

```powershell
.\targets\neoforge-1.21.1\gradlew.bat -p targets/neoforge-1.21.1 build --console plain --no-daemon
```

On POSIX use `bash ./targets/neoforge-1.21.1/gradlew` with the same arguments.
The output is `targets/neoforge-1.21.1/build/libs/GravityEngine-neoforge-1.21.1-0.0.2.jar`.

Use that JAR as a file dependency in a NeoForge 21.1.256 consumer; common is embedded.
Register provider IDs during initialization, create a session per Level, publish
immutable fields and report coverage for each query. Retain and close publication
leases on the source's declared lifecycle. Read the
[compiling provider example](verification/api-consumer/com/example/examplemod/gravity/ProviderFixture.java)
and [consumer example](verification/api-consumer/com/example/examplemod/gravity/ExternalConsumerFixture.java).

For Fabric 1.20.1, install JDK 25 for Gradle/Loom and JDK 17 for compilation.
The target's `gradle/gradle-daemon-jvm.properties` selects JDK 25 for the Gradle
daemon, independently of the Java 17 production toolchain. For an existing
IntelliJ IDEA import that explicitly selects another JVM, set this target's
Gradle JVM to JDK 25 under Settings > Build Tools > Gradle and reload the project.
Build from the command line with:

```powershell
.\targets\fabric-1.20.1\gradlew.bat -p targets/fabric-1.20.1 build --console plain --no-daemon
```

Only `api`, `api.field` and `api.math` are supported consumer namespaces.
FIELD coverage is per query; an empty registry never proves completeness.
Entity observations distinguish assignment, evaluated gravity and committed
application. DIRECT writes, arbitrary attitude transactions and collision providers
remain internal.

## Documentation

- [API contracts and 0.0.2 migration](docs/API_BOUNDARY.md)
- [Architecture, contributor instructions and verification](AGENTS.md)

Only `docs/API_BOUNDARY.md` is maintained in Git under `docs/`. Other local
documents are ignored and are not required by a fresh checkout.

## License

[GNU GPL version 3](LICENSE). Existing source notices remain in place. This checkout
contains no maintained third-party license index; this document does not invent one
or replace obligations attached to bundled dependencies.
