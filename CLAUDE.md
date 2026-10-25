# Reel Launcher

Custom Android TV / Google TV launcher for Craig's onn Google TV (192.168.1.128, adb over wireless debugging; ports change per pairing session).

- Kotlin, plain Android Views (no Compose), AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 26. Build with JDK 17.
- Genre data and per-region availability come from TMDB (`Tmdb.kt`). Key comes from `local.properties` (`tmdb.key`) or is set on the TV via `adb shell am start -n dev.craig.reel/.MainActivity --es tmdb_key KEY`.
- `Providers.kt` maps installed streaming-app packages to TMDB provider names, and handles launching titles (search deep link -> ACTION_SEARCH -> open app).
- The TV also has Projectivy Launcher (com.spocky.projengmenu). Stock launcher is com.google.android.apps.tv.launcherx. Do not disable it or set Reel as HOME unless Craig asks; always tell him the revert command.
- The sibling folder `../googletv` is a separate Rive CLI project; leave it alone unless asked.

## Toolchain (this machine)

No system JDK 17 or Gradle; both are installed through mise (no sudo needed):

- JDK 17: `~/.local/share/mise/installs/java/17` (`mise install java@17`)
- Gradle 8.11.1: `~/.local/share/mise/installs/gradle/8.11.1/gradle-8.11.1/bin/gradle` (only needed to regenerate the wrapper)
- Android SDK: `~/Android/Sdk` (cmdline-tools, platforms;android-35, build-tools 34/35, platform-tools), set in `local.properties`

## Gradle wrapper

The wrapper jar isn't checked in. Generate it once:

    JAVA_HOME=~/.local/share/mise/installs/java/17 ~/.local/share/mise/installs/gradle/8.11.1/gradle-8.11.1/bin/gradle wrapper --gradle-version 8.11.1

## Workflow

    JAVA_HOME=~/.local/share/mise/installs/java/17 ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell am start -n dev.craig.reel/.MainActivity
    adb exec-out screencap -p > /tmp/reel.png   # look at the result

After a UI change, install and screenshot to check it on the real TV.
