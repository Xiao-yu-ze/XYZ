#!/system/bin/sh
# 在本地开发时启动 Shizuku 的特权服务端。
#
# 以什么身份执行，Shizuku 就以什么身份运行：
#   su -c 'sh start_server.sh'      -> root   (uid 0)
#   adb shell 'sh start_server.sh'  -> shell  (uid 2000)  <- 普通手机等价路径
#
# 参数顺序与入口类为何如此，见 tools/shizuku/README.md。

ABI="${1:-x86_64}"

APK=$(pm path moe.shizuku.privileged.api | head -1 | cut -d: -f2)
if [ -z "$APK" ]; then
    echo "未安装 Shizuku" >&2
    exit 1
fi

LIBDIR=$(dirname "$APK")/lib/$ABI
if [ ! -d "$LIBDIR" ]; then
    echo "在该 ABI 下找不到原生库：$LIBDIR" >&2
    echo "可用的 ABI 有：" >&2
    ls "$(dirname "$APK")/lib" >&2
    exit 1
fi

export CLASSPATH="$APK"

# -Dshizuku.library.path 必须放在 /system/bin 前面：app_process 会先解析 VM 选项。
# 放到后面会被当成启动类名，报 ClassNotFoundException。
exec app_process -Dshizuku.library.path="$LIBDIR" /system/bin \
    --nice-name=shizuku_server \
    rikka.shizuku.server.ShizukuService
