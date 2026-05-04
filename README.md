<p align="center">
  <img src="docs/ic_firefly.svg" width="96" height="96" alt="KarooFireFly icon">
</p>

# KarooFireFly

ANT+ Smart Bike Light Controller extension for Hammerhead Karoo 3.

> **Early Development / Use at Your Own Risk**
>
> This extension uses an undocumented internal Karoo API that may break with firmware updates. Tested with Magene L508 and Garmin Varia RTL 515. Other ANT+ lights (Bontrager Ion/Flare, etc.) should work but are untested. Use at your own risk.

## Overview

Controls ANT+ bike lights paired through Karoo's native sensor settings. Unlike Karoo's built-in light support which only toggles on/off at ride start/stop, KarooFireFly sets specific light modes based on time of day and ambient light conditions.

**Features:**
- Discover and assign paired lights as Front or Rear with manufacturer info
- Independent feature switches: Time-based and Ambient Light Sensor (enable one or both)
- Automatic light mode switching based on time of day (sunrise/sunset with configurable offsets)
- Ambient light sensor mode switching (Karoo 3's built-in lux sensor)
- Combined mode: time-based baseline with ambient sensor override (e.g. tunnels)
- Two zones: Day and Night with clear "Day starts at" / "Night starts at" times
- Zone change notifications with sound during rides (configurable)
- Auto on/off with ride start/stop
- Auto-save — all settings take effect immediately
- Configurable light profiles per zone (Day / Night) with separate front and rear modes
- Multiple lights per role supported
- BonusActions mappable to AXS shift buttons or Karoo hardware buttons:
  - **Toggle Lights** — turns all lights on/off
  - **Cycle Mode** — cycles through modes (Off → Steady High → Steady Low → Slow Flash → Fast Flash)

## How It Works

Pair your ANT+ lights through **Karoo's native sensor settings** (Settings > Sensors). KarooFireFly discovers paired lights and shows them in the Connected Lights section where you assign each light as Front or Rear. When a ride starts, the extension automatically controls their mode based on your settings.

The extension communicates with Karoo's SensorService via its internal AIDL interface to send light mode commands. This means the extension works with whatever lights Karoo has already paired and connected — no need for separate ANT+ channel management.

## Settings

### Connected Lights

Discovered ANT+ lights are shown with their name and manufacturer. Assign each light as Front, Rear, or None.

<p align="center">
  <img src="docs/settings_lights.png" width="240" alt="Connected Lights">
</p>

### Light Control

Enable one or both features independently via switches:

| Feature | Description |
|---------|-------------|
| **Time-based (sunrise/sunset)** | Automatic mode switching at configurable times relative to sunrise/sunset |
| **Ambient Light Sensor** | Automatic mode switching based on Karoo 3's built-in lux sensor |

When both are enabled, time-based acts as the baseline and the ambient sensor can only darken the zone (e.g. tunnel detection). When neither is enabled, lights only respond to BonusButton presses.

<p align="center">
  <img src="docs/settings_timebased.png" width="240" alt="Time-based mode">
  <img src="docs/settings_ambient.png" width="240" alt="Ambient light mode">
</p>

### Zone Change Notifications

When enabled, an in-ride alert with sound is shown whenever the light zone changes (e.g. DAY → NIGHT). The notification shows the reason (sunrise/sunset or light sensor) and the resulting light modes for front and rear.

### Light Profiles

Configure which light mode to use for each zone (Day, Night) with separate front and rear settings. Changes are saved immediately.

<p align="center">
  <img src="docs/settings_profiles.png" width="240" alt="Light profiles">
</p>

### Manual Override

When using an auto mode, BonusButton presses temporarily override the automatic control. The override clears automatically when the light zone changes or when the ride state changes (pause/stop).

## Architecture

### Layers

1. **Karoo Integration** (`karoo/`) — SensorService AIDL binding, light mode commands
2. **Engine** (`engine/`) — State machine, sunrise/sunset calculation, ambient light sensor
3. **Data** (`data/`) — DataStore settings, light profiles, light assignments
4. **Extension** (`KarooLightControllerExtension.kt`) — KarooExtension service, entry point
5. **DataTypes** (`datatypes/`) — Graphical data field for ride screen
6. **UI** (`ui/`) — Jetpack Compose settings/profile screens

### State Machine (priority high to low)

1. **Manual Override** — BonusAction pressed, holds until zone change or ride state change
2. **Auto Mode** — Zone determined by configured control mode:
   - *Time-based:* sunrise/sunset with configurable offsets (±3 hours)
   - *Ambient Light:* lux sensor with smoothing (10s moving average) and hysteresis (10s dwell time)
   - *Combined:* time-based baseline, sensor can darken but not brighten (e.g. tunnel → NIGHT)
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
