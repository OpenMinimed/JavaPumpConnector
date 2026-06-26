# JavaPumpConnector

A skeleton Android app for communicating with Medtronic insulin pumps via the SAKE protocol.

## Prerequisites

- **JDK 17+** — required by Gradle 9.x. See `.java-version` or `gradle/wrapper/gradle-wrapper.properties`.
- **Android SDK** — with API 36 platform installed. Set the path in `local.properties`:
  ```properties
  sdk.dir=/path/to/Android/Sdk
  ```

## Getting Started

### 1. Clone with submodules

```bash
git clone --recurse-submodules https://github.com/OpenMinimed/JavaPumpConnector.git
```

If you already cloned without `--recurse-submodules`:

```bash
git submodule update --init
```

This pulls the [JavaSake](https://github.com/OpenMinimed/JavaSake) library into `JavaSake/`.

### 2. Build

```bash
./gradlew assembleDebug
```

The Gradle composite build (declared in `settings.gradle.kts`) resolves the
`org.openminimed:lib` dependency from the local `JavaSake/` subproject.

### 3. APK output

```
app/build/outputs/apk/debug/app-debug.apk
```

## Lint

```bash
./gradlew lint
```

Lint baseline is at `app/lint-baseline.xml`.
