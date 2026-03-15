# KarooFireFly

ANT+ Smart Bike Light Controller extension for Hammerhead Karoo 3.

## Overview

Intelligently controls ANT+ bike lights (Garmin Varia, Bontrager Ion/Flare, Magene L508, etc.) — unlike Karoo's native light support which only does basic on/off at ride start/stop.

**Features:**
- Time-based mode switching (day: flash, dusk: steady, night: full brightness)
- BonusActions for manual toggle/mode cycle (mappable to hardware buttons)
- Graphical data field showing light status on the ride screen
- Auto on/off with ride start/stop
- Uses Karoo-paired lights — no separate pairing needed

## How It Works

Pair your ANT+ lights through **Karoo's native sensor settings** (Settings > Sensors). When a ride starts, KarooFireFly automatically discovers the paired lights and controls their mode through Karoo's internal SensorService. No pairing UI in the extension — just configure your light profiles and time offsets.

The extension communicates with Karoo's SensorService via its internal AIDL interface (`LightCommandConnectionAIDL`) to send light mode commands. This means the extension works with whatever lights Karoo has already paired and connected — no need for separate ANT+ channel management.

## Architecture

### Layers

1. **Karoo Integration** (`karoo/`) — SensorService AIDL binding, light mode commands
2. **Engine** (`engine/`) — State machine, sunrise/sunset calculation
3. **Data** (`data/`) — DataStore settings, light profiles
4. **Extension** (`KarooLightControllerExtension.kt`) — KarooExtension service, entry point
5. **DataTypes** (`datatypes/`) — Graphical data field for ride screen
6. **UI** (`ui/`) — Jetpack Compose settings/profile screens

### State Machine (priority high to low)

1. **Manual Override** — BonusAction pressed, holds for 60s
2. **Time-based Mode** — DayTimeZone determines profile
3. **Ride State** — Lights off when ride ends

## Development Setup

### 1. JDK 17

```bash
brew install openjdk@17
```

Or install via [Android Studio](https://developer.android.com/studio) which bundles a JDK.

### 2. Android SDK

Either install [Android Studio](https://developer.android.com/studio) (recommended), or the command line tools only:

```bash
brew install --cask android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

Make sure `ANDROID_HOME` is set (Android Studio sets this automatically):

```bash
export ANDROID_HOME=~/Library/Android/sdk
```

### 3. GitHub Packages credentials

The karoo-ext SDK is hosted on GitHub Packages. Create a [GitHub personal access token](https://github.com/settings/tokens) with `read:packages` scope, then add to `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_GITHUB_TOKEN
```

### 4. Gradle Wrapper

Generate the wrapper (one-time, if not checked in):

```bash
gradle wrapper --gradle-version 8.5
```

Or if Gradle is not installed globally, Android Studio will handle this automatically.

## Build & Deploy

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Dependencies

- `io.hammerhead:karoo-ext:1.1.8` — Karoo Extension SDK
- `ca.rmen:lib-sunrise-sunset:1.1.1` — Sunrise/sunset calculation
- `androidx.datastore:datastore-preferences` — Settings persistence
- `androidx.compose.material3:material3` — UI
- `com.jakewharton.timber:timber` — Logging

## Important Notes

- Extension ID: `karoo-light-controller` (no `.` allowed in karoo-ext IDs)
- Pair lights through **Karoo's native sensor settings**, not through the extension

### Undocumented Karoo API Warning

This extension controls lights through Karoo's **internal SensorService AIDL interface** (`LightCommandConnectionAIDL`). This is not a public API — it was reverse-engineered from the SensorService APK.

**Why:** Karoo blocks `setRfFrequency()` for third-party apps, making it impossible to open ANT+ channels directly. The only way to control ANT+ lights is through Karoo's own SensorService, which already has an active ANT+ connection to paired lights.

**How it works:** The extension binds to the SensorService, retrieves the `LightCommandConnection` sub-binder via AIDL transaction 17, then calls `setLightMode` (transaction 3) with a `LightMode` Parcelable loaded via reflection from the SensorService's classloader.

**Risk:** Karoo firmware updates may change AIDL transaction codes, parameter formats, or class internals. The AIDL descriptor strings and data model class names (`io.hammerhead.datamodels.timeseriesData.models.LightMode`, `Device`) are stable non-obfuscated identifiers, but the transaction numbering could shift if Hammerhead adds or reorders methods. If the extension stops working after a firmware update, the `KarooLightControl.kt` file is the place to investigate.
