# PariKt

PariKt is a Kotlin Multiplatform library for evaluating [PARI/GP](https://pari.math.u-bordeaux.fr/) commands from Kotlin.

It supports JVM, Android (minSdk 21), JavaScript, WebAssembly, and Kotlin/Native for Linux x64,
Windows x64, macOS, iOS, tvOS, and watchOS. Apple tests ran on Intel and Apple Silicon Macs
and in Apple simulators. See the [Apple target guide](docs/APPLE_TARGETS.md) for coverage.

## Quick start

Choose an [installation method](#installation), then evaluate a GP command from a coroutine or
suspending function:

```kotlin
import com.elianfabian.parikt.PariKt

suspend fun factorExample() {
    PariKt.init()
    try {
        println(PariKt.eval("factor(123456)"))
    } finally {
        PariKt.close()
    }
}
```

## Installation

- [Gradle source installation](docs/SOURCE_INSTALLATION.md) and a
  [standalone consumer example](examples/consumer/README.md)
- [Maven coordinates and engine-tools setup](publishing/README.md#consumer-settings)

Select the PARI engine independently of the PariKt library version. Put the default in your
project’s root `parikt.properties` and use module-local files to override it:

```properties
pari.version=2.19.0
```

The build fetches and compiles engines on demand and reuses compatible cached outputs.

## Documentation

- [Platform setup, build commands, and sample apps](docs/PLATFORM_GUIDE.md)
- [Project structure and verification scripts](docs/CONTRIBUTOR_GUIDE.md)
- [Tested Kotlin versions](KOTLIN_COMPATIBILITY_PLAN.md)
- [Maven Central release process](docs/RELEASING.md)

PariKt is licensed under [GPL-3.0-or-later](LICENSE).
