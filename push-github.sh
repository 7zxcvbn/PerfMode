#!/usr/bin/env bash
# ============================================================
#  PerfMode - GitHub uploader
#  ASCII filename on purpose: Chinese names break inside zip on Termux.
#
#  Usage:  ./push-github.sh
#          (asks username + token, everything else is automatic)
#
#  It also works when Android blocks storage access:
#  falls back to the system file picker (termux-storage-get).
# ============================================================

say()  { printf '%s\n' "$1"; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

say "=============================================="
say " PerfMode upload helper"
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
    "$HOME/storage/downloads/PerfMode-工程.zip" \
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

  # ---------- 2b. storage blocked -> use the system file picker ----------
  if [ -z "$Z" ]; then
    step "Storage is blocked. Trying the file picker instead"
    say ""
    say "This needs the Termux:API companion app."
    say ""
    if ! command -v termux-storage-get >/dev/null 2>&1; then
      say "Installing termux-api..."
      pkg install termux-api -y
    fi

    if command -v termux-storage-get >/dev/null 2>&1; then
      say ""
      say ">>> A file picker will open on screen."
      say ">>> Find and tap:  PerfMode.zip"
      say ""
      printf 'Press Enter to open the picker...'
      read -r _
      # termux-storage-get copies the picked file to the given path
      PICKED=$(termux-storage-get "$HOME/PerfMode.zip" 2>/dev/null)
      if [ -f "$HOME/PerfMode.zip" ]; then
        Z="$HOME/PerfMode.zip"
        say "Got it."
      else
        say "Picker returned: ${PICKED:-<nothing>}"
      fi
    else
      say "termux-api not available."
    fi
  fi

  if [ -z "$Z" ]; then
    say ""
    say "Could NOT get PerfMode.zip."
    say ""
    say "Try this manually and tell me the output:"
    say "   rm -rf ~/storage && termux-setup-storage"
    say "   ls ~/storage/downloads"
    say ""
    say "If still blocked: Settings > Apps > Termux"
    say "  > Permissions > Files and media > Allow all files"
    exit 1
  fi

  say "Using: $Z"

  command -v unzip >/dev/null 2>&1 || {
    say "Installing unzip..."
    pkg install unzip -y
  }

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
printf '\nGitHub username [7zxcvbn]: '
read -r USER
USER=${USER:-7zxcvbn}

printf 'Repo name [PerfMode]: '
read -r REPO
REPO=${REPO:-PerfMode}

say ""
say "----------------------------------------------"
say " Now you need a token (a password for git)"
say "----------------------------------------------"
say ""
say " 1. In browser (enable DESKTOP SITE) open:"
say "      github.com/settings/tokens"
say " 2. Generate new token (classic)"
say " 3. Tick  repo"
say " 4. Generate, then COPY the ghp_... string"
say ""
say "Tip: create the empty repo $REPO on GitHub first."
say ""
printf 'Paste token here (hidden): '
stty -echo 2>/dev/null
read -r TOKEN
stty echo 2>/dev/null
say ""

[ -z "$TOKEN" ] && { say "No token, aborting."; exit 1; }

# Strip anything curl would reject: CR, LF, spaces, zero-width, control chars.
# This is what causes "URL rejected: Malformed input to a URL function".
TOKEN=$(printf '%s' "$TOKEN" | tr -cd 'A-Za-z0-9_-')
USER=$(printf '%s' "$USER"  | tr -cd 'A-Za-z0-9-')
REPO=$(printf '%s' "$REPO"  | tr -cd 'A-Za-z0-9._-')

say "Cleaned token length: ${#TOKEN}  (should be 40)"

# ---------- 5. push ----------
step "Uploading"
set +o history 2>/dev/null || true

git init -q 2>/dev/null
git config user.email "$USER@users.noreply.github.com"
git config user.name "$USER"
git add -A
git commit -q -m "init" 2>/dev/null || say "  (nothing new to commit)"
git branch -M main

PUSH_OK=0

# --- attempt 1: token inside the URL (no typing needed) ---
git remote remove origin 2>/dev/null
git remote add origin "https://$USER:$TOKEN@github.com/$USER/$REPO.git"
say "Attempt 1: token in URL"
if git push -u origin main 2>&1; then
  PUSH_OK=1
fi

# --- attempt 2: clean URL, let git ask for credentials ---
# This avoids "URL rejected: Malformed input to a URL function" caused by
# invisible characters pasted together with the token.
if [ "$PUSH_OK" != "1" ]; then
  say ""
  say "Attempt 1 failed. Retrying with a clean URL."
  say ""
  say ">>> git will now ask you to log in:"
  say ">>>   Username: type   $USER"
  say ">>>   Password: paste the token (invisible, just press Enter)"
  say ""
  git remote remove origin 2>/dev/null
  git remote add origin "https://github.com/$USER/$REPO.git"
  if git push -u origin main 2>&1; then
    PUSH_OK=1
  fi
fi

if [ "$PUSH_OK" = "1" ]; then
  say ""
  say "=============================================="
  say " SUCCESS - code is on GitHub"
  say "=============================================="
  say ""
  say "Last step, open in browser:"
  say ""
  say "   github.com/$USER/$REPO/actions"
  say ""
  say "Tap ALLOW to enable Actions."
  say "Then: build apk -> Run workflow -> Run workflow"
  say "Wait 3-5 min, download APK from Artifacts at page bottom."
  say ""
else
  say ""
  say "PUSH FAILED. Usual causes:"
  say "  1. Repo $REPO does not exist yet (create it empty + Public first)"
  say "  2. Token wrong, or 'repo' scope not ticked"
  say "  3. Username typo (should be 7zxcvbn - xc, not cb)"
  say "  4. No network"
  say ""
  say "Screenshot the red error above and send it to me."
fi
