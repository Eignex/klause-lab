#!/usr/bin/env bash
# Build klause-lab and register its api and runner services with the host's service manager:
# launchd on macOS (boot-time daemons that run as this user), systemd user units on Linux.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
envfile="$HOME/.config/klause-lab/lab.env"
[[ -f "$envfile" ]] || { mkdir -p "$(dirname "$envfile")"; cp "$here/deploy/lab.env.example" "$envfile"; }
unset JAVA_HOME
set -a; . "$envfile"; set +a
LAB_DATA="${LAB_DATA:-$HOME/klause-lab-data}"
mkdir -p "$LAB_DATA/logs"

# Gradle itself needs a Java to launch; any 17+ on PATH does. The services run on the toolchain JDK.
command -v java >/dev/null || [[ -n "${JAVA_HOME:-}" ]] || { echo "install any JDK 17+ so Gradle can launch"; exit 1; }
(cd "$here" && ./gradlew installDist --max-workers="${LAB_GRADLE_WORKERS:-2}" -q)
JAVA_HOME="${JAVA_HOME:-$(cd "$here" && ./gradlew -q printJavaHome)}"
export JAVA_HOME
echo "services run on $JAVA_HOME"
bin="$here/build/install/klause-lab/bin/klause-lab"
"$bin" check   # fails here, before anything is registered, on a host without BLAS

case "$(uname -s)" in
Darwin)
  for role in api runner; do
    label="com.eignex.klause-lab.$role"
    plist="/Library/LaunchDaemons/$label.plist"
    {
      cat <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>$label</string>
  <key>UserName</key><string>$(id -un)</string>
  <key>ProgramArguments</key><array><string>$bin</string><string>$role</string></array>
  <key>WorkingDirectory</key><string>$LAB_DATA</string>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ThrottleInterval</key><integer>30</integer>
  <key>StandardOutPath</key><string>$LAB_DATA/logs/$role.log</string>
  <key>StandardErrorPath</key><string>$LAB_DATA/logs/$role.log</string>
  <key>EnvironmentVariables</key><dict>
    <key>PATH</key><string>$PATH</string>
    <key>HOME</key><string>$HOME</string>
    <key>JAVA_HOME</key><string>$JAVA_HOME</string>
PLIST
      grep -v '^\s*#' "$envfile" | grep '=' | grep -v '^JAVA_HOME=' | while IFS='=' read -r key value; do
        value="${value%\"}"; value="${value#\"}"
        printf '    <key>%s</key><string>%s</string>\n' "$key" "$value"
      done
      echo '  </dict>'
      echo '</dict></plist>'
    } > "/tmp/$label.plist"
    sudo launchctl bootout system "$plist" 2>/dev/null || true
    sudo install -m 644 -o root -g wheel "/tmp/$label.plist" "$plist"
    sudo launchctl bootstrap system "$plist"
  done
  echo "keep the machine awake for queued work: sudo pmset -a sleep 0 disksleep 0"
  ;;
Linux)
  units="$HOME/.config/systemd/user"
  mkdir -p "$units"
  for role in api runner; do
    cat > "$units/klause-lab-$role.service" <<UNIT
[Unit]
Description=klause lab $role
After=network-online.target

[Service]
EnvironmentFile=$envfile
Environment=PATH=$PATH
Environment=JAVA_HOME=$JAVA_HOME
WorkingDirectory=$LAB_DATA
ExecStart=$bin $role
Restart=on-failure
RestartSec=30
# The host check exits 78 on a machine without BLAS; retrying cannot fix that.
RestartPreventExitStatus=78
StandardOutput=append:$LAB_DATA/logs/$role.log
StandardError=append:$LAB_DATA/logs/$role.log

[Install]
WantedBy=default.target
UNIT
  done
  systemctl --user daemon-reload
  systemctl --user enable --now klause-lab-api klause-lab-runner
  loginctl enable-linger "$(id -un)"   # start at boot without a login session
  ;;
*) echo "unsupported OS $(uname -s)"; exit 1 ;;
esac
echo "api on http://$(hostname):${LAB_PORT:-8420}/"
