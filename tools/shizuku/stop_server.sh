#!/system/bin/sh
# 停止 Shizuku 服务端并清空授权记录，让下次启动 App 时表现为「首次运行」。
# 这是开发辅助脚本，详见 tools/shizuku/README.md。

pkill -f shizuku_server
sleep 1

# App 启动的 UserService 运行在独立进程里（进程名形如 包名:后缀）。
pkill -f "com.pvz2.auto:pvz2"
sleep 1

# Shizuku 把已授予的权限记录在这里，删掉即可强制重新弹授权框。
rm -f /data/user_de/0/com.android.shell/shizuku.json

echo "--- 剩余进程 ---"
ps -A | grep -iE "shizuku|pvz2"
