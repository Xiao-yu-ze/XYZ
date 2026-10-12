# Shizuku bring-up helpers

Scripts used to start the Shizuku privileged server on a **test device** without going
through the Shizuku app's own onboarding UI. Handy for emulators and CI.

On a real phone you do **not** need any of this - the Shizuku app starts its own server.
These exist so an agent (or you) can drive the whole stack from `adb` during development.

## How the server is started (verified against Shizuku 13.6.0)

The entry point was recovered by decompiling the shipped APK, not guessed:

```
rikka.shizuku.server.ShizukuService.main(String[])
    DdmHandleAppName.setAppName("shizuku_server", 0)
    xh.p = System.getProperty("shizuku.library.path")   // <-- required
    Looper.prepareMainLooper()
    new ShizukuService()
    Looper.loop()
```

So the launch is:

```sh
APK=$(pm path moe.shizuku.privileged.api | head -1 | cut -d: -f2)
LIBDIR=$(dirname "$APK")/lib/<abi>
export CLASSPATH="$APK"
app_process -Dshizuku.library.path="$LIBDIR" /system/bin \
    --nice-name=shizuku_server \
    rikka.shizuku.server.ShizukuService
```

### Two things that will bite you

1. **`-D` must come *before* `/system/bin`.**
   `app_process` parses VM options before the command directory. Putting `-D...` after
   `/system/bin` makes it be interpreted as the start class, and the process dies with:

   ```
   ClassNotFoundException: -Dshizuku.library.path=...
   No pending exception expected
   ```

2. **`moe.shizuku.starter.ServiceStarter` is NOT the server entry point.**
   Despite the name it is the *UserService* launcher. It parses `--package=`, `--class=`,
   `--uid=`, `--token=` and instantiates a class from an APK inside Shizuku's own
   `app_process`. Running it bare gives `NullPointerException` inside
   `ContextImpl.createPackageContextAsUser`. Use `rikka.shizuku.server.ShizukuService`.

## Which identity will Shizuku run as?

Whatever identity runs the script:

| Command | Server uid | Notes |
|---|---|---|
| `su -c 'sh start_server.sh'` | `root` (0) | full root privileges |
| `adb shell 'sh start_server.sh'` | `shell` (2000) | **the realistic path for retail phones** |

The app reports this on screen as `Shizuku: ready (uid N)`.

Our automation only needs `screencap`, `input` and `wm size`, all of which are already
available to `shell`, so **uid 2000 is sufficient and no root is required**.

## Usage

```sh
# 1. push the script for the target ABI (x86_64 shown; use arm64-v8a on a phone)
adb push tools/shizuku/start_server.sh /data/local/tmp/start_server.sh
adb shell chmod 755 /data/local/tmp/start_server.sh

# 2. start it as shell (uid 2000) - no root needed
adb shell 'nohup sh /data/local/tmp/start_server.sh >/dev/null 2>&1 &'

# 3. confirm
adb shell 'ps -A -o USER,PID,NAME | grep shizuku_server'
#    expect:  shell   <pid>  shizuku_server
```

Then launch the app and tap **AUTHORISE SHIZUKU**, and confirm in Shizuku's dialog.

`stop_server.sh` kills the server plus any running UserService and clears the stored
authorisation, which is how you reproduce a first-run experience.
