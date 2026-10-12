#!/system/bin/sh
# Starts the Shizuku privileged server for LOCAL DEVELOPMENT.
#
# Execution identity decides the privilege level:
#   su -c 'sh start_server.sh'   -> root       (uid 0)
#   adb shell 'sh start_server.sh' -> shell    (uid 2000)   <- retail-phone equivalent
#
# See tools/shizuku/README.md for why the argument order and entry class matter.

ABI="${1:-x86_64}"

APK=$(pm path moe.shizuku.privileged.api | head -1 | cut -d: -f2)
if [ -z "$APK" ]; then
    echo "Shizuku is not installed" >&2
    exit 1
fi

LIBDIR=$(dirname "$APK")/lib/$ABI
if [ ! -d "$LIBDIR" ]; then
    echo "no native libs for ABI '$ABI' at $LIBDIR" >&2
    echo "available:" >&2
    ls "$(dirname "$APK")/lib" >&2
    exit 1
fi

export CLASSPATH="$APK"

# -Dshizuku.library.path MUST precede /system/bin: app_process parses VM options first.
exec app_process -Dshizuku.library.path="$LIBDIR" /system/bin \
    --nice-name=shizuku_server \
    rikka.shizuku.server.ShizukuService
