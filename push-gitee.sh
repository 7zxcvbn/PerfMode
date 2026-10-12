#!/usr/bin/env bash
# ============================================================
#  PerfMode - upload to Gitee (China-friendly alternative to GitHub)
#  Gitee is reachable from mainland China where GitHub often times out.
#
#  Usage:  ./push-gitee.sh
#          (asks username + token, everything else is automatic)
#
#  Get a Gitee token: gitee.com/profile/personal_access_tokens
# ============================================================

say()  { printf '%s\n' "$1"; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

say "=============================================="
say " PerfMode upload helper  (Gitee edition)"
say "=============================================="

# ---------- 0. make sure storage is set up ----------
if command -v termux-setup-storage >/dev/null 2>&1 && [ ! -d "$HOME/storage/downloads" ]; then
  step "Setting up storage access"
  say "If it asks to wipe ~/storage -> type  y  and press Enter"
  rm -rf "$HOME/storage" 2>/dev/null
  termux-setup-storage
  sleep 3
fi

# ---------- 1. locate project ----------
step "Looking for project"
PROJ=""
for d in "." "./PerfMode" "$HOME/PerfMode"; do
  [ -f "$d/settings.gradle.kts" ] && PROJ="$d" && break
done

# ---------- 2. if missing, find the zip ----------
if [ -z "$PROJ" ]; then
  say "Project not found. Looking for the zip..."
  Z=""
  for p in \
    "$HOME/storage/downloads/PerfMode.zip" \
    "$HOME/storage/download/PerfMode.zip" \
    "$HOME/downloads/PerfMode.zip" \
    "$HOME/PerfMode.zip" \
    "/sdcard/Download/PerfMode.zip" \
    "/storage/emulated/0/Download/PerfMode.zip" \
    "./PerfMode.zip"
  do
    [ -f "$p" ] && Z="$p" && break
  done

  if [ -z "$Z" ] && [ -d "$HOME/storage/downloads" ]; then
    f=$(ls "$HOME/storage/downloads" 2>/dev/null | grep -i 'perfmode.*\.zip$' | head -1)
    [ -n "$f" ] && Z="$HOME/storage/downloads/$f"
  fi

  if [ -z "$Z" ]; then
    say ""
    say "Could NOT find PerfMode.zip."
    say "Try:  rm -rf ~/storage && termux-setup-storage"
    say "Then: ls ~/storage/downloads"
    exit 1
  fi

  say "Using: $Z"
  command -v unzip >/dev/null 2>&1 || { say "Installing unzip..."; pkg install unzip -y; }

  cd "$HOME" || exit 1
  rm -rf PerfMode
  unzip -q -o "$Z" || { say "unzip failed"; exit 1; }
  PROJ="$HOME/PerfMode"
fi

cd "$PROJ" || { say "Cannot enter $PROJ"; exit 1; }
say "Project: $(pwd)"

# ---------- 3. git ----------
step "Checking git"
command -v git >/dev/null 2>&1 || {
  say "Installing git (a few minutes, keep screen on)..."
  pkg install git -y || { say "git install failed, run: pkg install git"; exit 1; }
}
say "git OK"

# ---------- 4. ask user ----------
printf '\nGitee username (e.g. 12345678): '
read -r USER
[ -z "$USER" ] && { say "No username, aborting."; exit 1; }

printf 'Repo name [PerfMode]: '
read -r REPO
REPO=${REPO:-PerfMode}

say ""
say "----------------------------------------------"
say " Need a Gitee access token"
say "----------------------------------------------"
say ""
say " 1. Browser -> gitee.com/profile/personal_access_tokens"
say " 2. Generate new token"
say " 3. Tick: projects (and user info)"
say " 4. Submit, then COPY the token string"
say ""
say "Tip: create the repo $REPO on Gitee first,"
say "     and make it PUBLIC (build quota needs it)."
say ""
printf 'Paste token here (hidden): '
stty -echo 2>/dev/null
read -r TOKEN
stty echo 2>/dev/null
say ""

[ -z "$TOKEN" ] && { say "No token, aborting."; exit 1; }

# ---------- 5. push ----------
step "Uploading to Gitee"
set +o history 2>/dev/null || true

git init -q 2>/dev/null
git config user.email "$USER@noreply.gitee.com"
git config user.name "$USER"
git add -A
git commit -q -m "init" 2>/dev/null || say "  (nothing new to commit)"
git branch -M master
git remote remove origin 2>/dev/null
git remote add origin "https://$USER:$TOKEN@gitee.com/$USER/$REPO.git"

if git push -u origin master 2>&1; then
  say ""
  say "=============================================="
  say " SUCCESS - code is on Gitee"
  say "=============================================="
  say ""
  say "Next: build the APK in the cloud"
  say ""
  say " 1. Open:  gitee.com/$USER/$REPO"
  say " 2. Top menu ->  Service (服务) ->  Android build (安卓构建)"
  say " 3. New build (新建构建):"
  say "      branch: master"
  say "      build type: release"
  say " 4. Wait a few minutes, then download the APK"
  say "    from the build result page."
  say ""
  say "If there is no Android build menu, use Gitee Go instead:"
  say "  Pipeline (流水线) -> New pipeline -> pick Gradle,"
  say "  JDK 17, Gradle 8.9, command:"
  say "      gradle assembleRelease --no-daemon"
  say "  artifact path: app/build/outputs/apk/release"
  say ""
else
  say ""
  say "PUSH FAILED. Usual causes:"
  say "  1. Token wrong, or wrong permissions ticked"
  say "  2. Repo $REPO does not exist yet"
  say "  3. Repo is private (make it public)"
  say "  4. No network"
  say ""
  say "Screenshot the red error and send it to me."
fi
