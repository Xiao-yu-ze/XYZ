#!/system/bin/sh
# Stops the Shizuku server and resets authorisation, so the next app launch behaves like
# a first run. Development helper - see tools/shizuku/README.md.

pkill -f shizuku_server
sleep 1

# The UserService the app started lives in its own process (package:suffix).
pkill -f "com.pvz2.auto:pvz2"
sleep 1

# Shizuku stores granted permissions here. Removing it forces a fresh consent dialog.
rm -f /data/user_de/0/com.android.shell/shizuku.json

echo "--- remaining processes ---"
ps -A | grep -iE "shizuku|pvz2"
