#!/usr/bin/env bash
# ============================================================================
#  upload.sh v2.0 - Upload project (folder / arsip) ke GitHub dari Termux
# ============================================================================
#  Contoh:
#    bash upload.sh                              (mode menu interaktif)
#    bash upload.sh /storage/emulated/0/projek public
#    bash upload.sh /storage/emulated/0/novatube.zip private
#    bash upload.sh A B C.zip public             (banyak project sekaligus)
#    bash upload.sh projek --dry-run             (simulasi, tidak upload)
#    bash upload.sh --status | --token | --logout
# ============================================================================

VERSION="2.0"
set -uo pipefail

# ---------------------------- KONFIGURASI ----------------------------------
GH_USER_DEFAULT="fadil111example-netizen"
GH_EMAIL="fadil111example@gmail.com"
TOKEN_FILE="$HOME/.upload_gh_token"
LOG="$HOME/.upload_gh.log"
MAX_FILE_MB=100
# ---------------------------------------------------------------------------

R=$'\033[1;31m'; G=$'\033[1;32m'; Y=$'\033[1;33m'; B=$'\033[1;36m'; D=$'\033[2m'; N=$'\033[0m'
_log() { printf '%s %s\n' "$(date '+%F %T')" "$*" >> "$LOG" 2>/dev/null || true; }
info() { printf '%s[*]%s %s\n' "$B" "$N" "$*"; _log "INFO $*"; }
ok()   { printf '%s[+]%s %s\n' "$G" "$N" "$*"; _log "OK   $*"; }
warn() { printf '%s[!]%s %s\n' "$Y" "$N" "$*"; _log "WARN $*"; }
die()  { printf '%s[x]%s %s\n' "$R" "$N" "$*" >&2; _log "ERR  $*"; exit 1; }

usage() {
  cat <<EOF
upload.sh v$VERSION - upload project ke GitHub dari Termux

Pemakaian:
  bash upload.sh [opsi] <folder|arsip> ... [public|private]
  bash upload.sh                    Mode menu (pilih project dari HP)

Arsip yang didukung: .zip .tar .tar.gz .tgz .tar.bz2 .tar.xz .7z(butuh p7zip)

Opsi:
  --public / --private   Visibilitas repo baru (default: private)
  --name NAMA            Nama repo kustom (hanya untuk 1 project)
  --desc TEKS            Deskripsi repo
  --branch NAMA          Branch tujuan (default: branch utama repo / main)
  --msg TEKS             Pesan commit
  --no-delete            Repo lama: jangan hapus file yang tidak ada di project
  --all                  Jangan abaikan node_modules, __pycache__, dll
  --dry-run              Simulasi saja, tidak membuat repo / push
  -y, --yes              Jangan tanya apa pun (otomatis jawab ya)
  --status               Cek token & koneksi
  --token                Isi / ganti token
  --logout               Hapus token tersimpan
  -v, --version | -h, --help
Log: $LOG
EOF
}

# ------------------------------ ARGUMEN ------------------------------------
OPT_NAME=""; OPT_DESC=""; OPT_BRANCH=""; OPT_MSG=""
DRY=0; YES=0; ALL=0; NODEL=0; FORCE_TOKEN=0; MODE=run; VIS=""; VIS_SET=0
inputs=()
while [ $# -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    -v|--version) echo "upload.sh v$VERSION"; exit 0 ;;
    --token|--reset-token) FORCE_TOKEN=1; MODE=token ;;
    --status) MODE=status ;;
    --logout) MODE=logout ;;
    --dry-run) DRY=1 ;;
    -y|--yes) YES=1 ;;
    --all) ALL=1 ;;
    --no-delete) NODEL=1 ;;
    --public) VIS=public; VIS_SET=1 ;;
    --private) VIS=private; VIS_SET=1 ;;
    --name|--desc|--branch|--msg)
      [ $# -ge 2 ] || die "Opsi $1 membutuhkan nilai."
      case "$1" in
        --name) OPT_NAME="$2" ;; --desc) OPT_DESC="$2" ;;
        --branch) OPT_BRANCH="$2" ;; --msg) OPT_MSG="$2" ;;
      esac
      shift ;;
    --name=*) OPT_NAME="${1#*=}" ;;
    --desc=*) OPT_DESC="${1#*=}" ;;
    --branch=*) OPT_BRANCH="${1#*=}" ;;
    --msg=*) OPT_MSG="${1#*=}" ;;
    -*) die "Opsi tidak dikenal: $1 (lihat --help)" ;;
    *)
      lc="${1,,}"
      if [ "$lc" = public ] || [ "$lc" = private ]; then VIS="$lc"; VIS_SET=1
      else inputs+=("$1"); fi ;;
  esac
  shift
done

if [ "$MODE" = logout ]; then
  rm -f "$TOKEN_FILE"; ok "Token tersimpan sudah dihapus."; exit 0
fi

# ------------------------------ TEMP DIR -----------------------------------
T=$(mktemp -d 2>/dev/null || mktemp -d -p "$HOME") || die "Gagal membuat folder temp."
cleanup() { rm -rf "$T"; }
trap cleanup EXIT
trap 'echo; die "Dibatalkan oleh pengguna."' INT TERM

# ------------------------- DEPENDENSI (auto install) -----------------------
need=()
for c in git curl unzip tar base64 timeout; do command -v "$c" >/dev/null 2>&1 || need+=("$c"); done
if [ ${#need[@]} -gt 0 ]; then
  info "Menginstall: ${need[*]}"
  if command -v pkg >/dev/null 2>&1; then
    pkgs=()
    for c in "${need[@]}"; do
      case "$c" in base64|timeout) pkgs+=(coreutils) ;; *) pkgs+=("$c") ;; esac
    done
    pkg install -y "${pkgs[@]}" >/dev/null 2>&1 || pkg install -y "${pkgs[@]}" || die "Gagal install: ${pkgs[*]}"
  else
    die "Install manual dulu: ${need[*]}"
  fi
fi

# ------------------------------ API HELPER ---------------------------------
TOKEN=""; LOGIN=""; CODE=000
api() {  # api METHOD URL [JSON] -> $CODE ; body: $T/body ; header: $T/hdr
  local m=$1 u=$2 d=${3:-}
  local ca=(-sS -m 30 -o "$T/body" -D "$T/hdr" -w '%{http_code}' -X "$m"
            -H "Authorization: Bearer $TOKEN"
            -H "Accept: application/vnd.github+json"
            -H "X-GitHub-Api-Version: 2022-11-28")
  [ -n "$d" ] && ca+=(-H "Content-Type: application/json" -d "$d")
  : > "$T/body"; : > "$T/hdr"
  CODE=$(curl "${ca[@]}" "$u" 2>/dev/null) || true
  [ -z "$CODE" ] && CODE=000
}
json_get()  { sed -n "s/.*\"$1\": *\"\\([^\"]*\\)\".*/\\1/p" "$T/body" | head -1; }
json_esc()  { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n\r\t' '   '; }
interactive() { [ -t 0 ] && [ -t 1 ]; }
confirm() {  # confirm "pertanyaan" -> 0 = ya (default ya)
  [ "$YES" -eq 1 ] && return 0
  interactive || return 0
  local a; read -rp "$1 [Y/n] " a
  case "${a,,}" in n|no|t|tidak) return 1 ;; *) return 0 ;; esac
}

# ------------------------------- TOKEN -------------------------------------
save_token() { ( umask 077; printf '%s\n' "$TOKEN" > "$TOKEN_FILE" ); chmod 600 "$TOKEN_FILE" 2>/dev/null; }

show_token_info() {
  LOGIN=$(json_get login); [ -z "$LOGIN" ] && LOGIN="$GH_USER_DEFAULT"
  ok "Token aktif. Login sebagai: $LOGIN"
  local exp ep days now scopes
  exp=$(grep -i '^github-authentication-token-expiration:' "$T/hdr" | cut -d' ' -f2- | tr -d '\r')
  if [ -n "$exp" ]; then
    ep=$(date -d "$exp" +%s 2>/dev/null || echo "")
    if [ -n "$ep" ]; then
      now=$(date +%s); days=$(( (ep - now) / 86400 ))
      info "Token berlaku sampai: $exp (sisa ~${days} hari)"
      [ "$days" -le 7 ] && warn "Token segera kedaluwarsa, siapkan token baru."
    else info "Token berlaku sampai: $exp"; fi
  else
    info "Token tanpa tanggal kedaluwarsa (atau tidak dilaporkan GitHub)."
  fi
  scopes=$(grep -i '^x-oauth-scopes:' "$T/hdr" | cut -d: -f2- | tr -d '\r ')
  if [ -n "$scopes" ] && ! printf '%s' "$scopes" | grep -Eq '(^|,)(repo|public_repo)(,|$)'; then
    warn "Token classic ini tidak punya scope 'repo' -> upload bisa gagal."
  fi
}

ask_token() {
  local n=0 t
  while [ $n -lt 3 ]; do
    read -rsp "Masukkan GitHub token (ghp_... / github_pat_...): " t </dev/tty || die "Tidak bisa membaca input token."
    echo
    t=$(printf '%s' "$t" | tr -d '[:space:]'); n=$((n+1))
    [ -z "$t" ] && { warn "Token kosong."; continue; }
    TOKEN=$t; api GET https://api.github.com/user
    case "$CODE" in
      200) save_token; ok "Token valid & disimpan (hanya perlu sekali)."; show_token_info; return 0 ;;
      401) warn "Token ditolak GitHub (salah / expired). Coba lagi." ;;
      000) die "Tidak ada koneksi internet." ;;
      *)   die "GitHub membalas HTTP $CODE saat cek token." ;;
    esac
  done
  die "Gagal 3x memasukkan token."
}

get_token() {
  if [ "$FORCE_TOKEN" -eq 0 ] && [ -s "$TOKEN_FILE" ]; then
    TOKEN=$(tr -d '[:space:]' < "$TOKEN_FILE")
    info "Mengecek masa aktif token..."
    api GET https://api.github.com/user
    case "$CODE" in
      200) show_token_info; return 0 ;;
      401) warn "Token lama sudah kedaluwarsa / dicabut / tidak valid. Minta token baru."; rm -f "$TOKEN_FILE" ;;
      000) die "Tidak ada koneksi internet (token tidak bisa dicek)." ;;
      403|429) die "GitHub membatasi request (HTTP $CODE). Coba lagi beberapa saat." ;;
      *)   die "GitHub membalas HTTP $CODE saat cek token." ;;
    esac
  else
    info "Token belum ada / diminta ganti."
  fi
  ask_token
}

if [ "$MODE" = token ] || [ "$MODE" = status ]; then get_token; exit 0; fi

# ----------------------------- MODE MENU -----------------------------------
pick_project() {
  local base="/storage/emulated/0"; [ -d "$base" ] || base="$PWD"
  local items=() i=0 ch f
  while IFS= read -r f; do items+=("$f"); done < <(
    find "$base" -mindepth 1 -maxdepth 1 \
      \( -type d -o -type f \( -iname '*.zip' -o -iname '*.tar' -o -iname '*.tar.gz' -o -iname '*.tgz' -o -iname '*.7z' \) \) \
      ! -name '.*' ! -name Android ! -name DCIM ! -name Pictures ! -name Music ! -name Movies \
      ! -name Alarms ! -name Notifications ! -name Podcasts ! -name Ringtones ! -name Recordings \
      2>/dev/null | sort | head -40)
  echo "Project di $base:"
  for f in "${items[@]}"; do
    i=$((i+1))
    if [ -d "$f" ]; then printf '  %2d) [DIR] %s\n' "$i" "$(basename "$f")"
    else printf '  %2d) [ARC] %s\n' "$i" "$(basename "$f")"; fi
  done
  read -rp "Pilih nomor (boleh banyak, pisah spasi) atau ketik path: " ch
  for f in $ch; do
    if [[ "$f" =~ ^[0-9]+$ ]] && [ "$f" -ge 1 ] && [ "$f" -le "${#items[@]}" ]; then
      inputs+=("${items[$((f-1))]}")
    elif [ -e "$f" ]; then inputs+=("$f")
    else warn "Diabaikan: $f"; fi
  done
  if [ "$VIS_SET" -eq 0 ]; then
    read -rp "Visibilitas [public/private] (default private): " ch
    ch="${ch,,}"; [ "$ch" = public ] && VIS=public
  fi
}

if [ ${#inputs[@]} -eq 0 ]; then
  if interactive; then pick_project; else usage; exit 1; fi
  [ ${#inputs[@]} -eq 0 ] && die "Tidak ada project yang dipilih."
fi

[ -z "$VIS" ] && VIS=private
case "$VIS" in public) PRIV=false ;; *) PRIV=true ;; esac
[ -n "$OPT_NAME" ] && [ ${#inputs[@]} -gt 1 ] && die "--name hanya bisa untuk 1 project."

get_token

# ------------------------ GIT HELPER (dynamic scope) -----------------------
export GIT_TERMINAL_PROMPT=0
g()  { git --git-dir="$GITDIR" --work-tree="$ROOT" -c safe.directory='*' "$@"; }
ga() { g -c credential.helper= -c "http.extraheader=Authorization: Basic $B64" -c http.postBuffer=524288000 "$@"; }
excl_add() { printf '/%s\n' "$(printf '%s' "$1" | sed 's/[][*?!#\\ ]/\\&/g')" >> "$GITDIR/info/exclude"; }
sanitize_name() {
  local n
  n=$(printf '%s' "$1" | sed 's/[^A-Za-z0-9._-]/-/g; s/-\{2,\}/-/g; s/^[-.]*//; s/-*$//')
  n="${n%.git}"; n="${n:0:100}"; [ -z "$n" ] && n="project"
  printf '%s' "$n"
}

extract_archive() {  # extract_archive FILE DEST -> set ROOT NAME
  local f=$1 EX=$2 lc="${1,,}" base bin
  mkdir -p "$EX"
  case "$lc" in
    *.zip)
      unzip -tq "$f" >/dev/null 2>&1 || die "Zip rusak / berpassword / tidak bisa dibaca."
      info "Mengekstrak zip..."; unzip -q -o "$f" -d "$EX" || die "Gagal ekstrak zip." ;;
    *.tar|*.tar.gz|*.tgz|*.tar.bz2|*.tbz2|*.tar.xz|*.txz)
      info "Mengekstrak tar..."; tar -xf "$f" -C "$EX" || die "Gagal ekstrak arsip tar." ;;
    *.7z)
      bin=""; for c in 7z 7za 7zz; do command -v "$c" >/dev/null 2>&1 && { bin=$c; break; }; done
      [ -z "$bin" ] && die "Untuk .7z install dulu: pkg install p7zip"
      info "Mengekstrak 7z..."; "$bin" x -y -o"$EX" "$f" >/dev/null || die "Gagal ekstrak 7z." ;;
    *) die "Format tidak didukung: $f" ;;
  esac
  rm -rf "$EX/__MACOSX"; find "$EX" -name '.DS_Store' -delete 2>/dev/null
  base=$(basename "$f")
  base=$(printf '%s' "$base" | sed -E 's/\.(tar\.(gz|bz2|xz)|tgz|tbz2|txz|tar|zip|7z)$//I')
  local tops=()
  while IFS= read -r c; do tops+=("$c"); done < <(find "$EX" -mindepth 1 -maxdepth 1)
  if [ ${#tops[@]} -eq 1 ] && [ -d "${tops[0]}" ]; then
    ROOT="${tops[0]}"; NAME=$(basename "$ROOT")
    ok "Folder root di dalam arsip: $NAME (jadi nama repository)"
  else
    ROOT="$EX"; NAME="$base"
    ok "Arsip tanpa folder root tunggal, nama repo dari nama arsip: $NAME"
  fi
}

# ============================ PROSES 1 PROJECT ==============================
process_project() {
  local IN="$1" idx="$2" P="$T/p$2" start=$SECONDS
  local ROOT="" NAME="" EXISTS=0 DEFAULT_BR="" REPO_PRIV="" BRANCH="" IS_PUBLIC=1
  local GITDIR="$P/gitdir" B64 URL COUNT MSG DESC
  mkdir -p "$P"

  [ -e "$IN" ] || die "Path tidak ditemukan: $IN
    Jika di /storage/emulated/0, jalankan dulu: termux-setup-storage"
  IN=$(realpath -- "$IN" 2>/dev/null || printf '%s' "$IN"); IN="${IN%/}"

  if [ -d "$IN" ]; then
    ROOT="$IN"; NAME=$(basename "$IN"); info "Mode: FOLDER -> $ROOT"
  elif [ -f "$IN" ]; then
    info "Mode: ARSIP -> $IN"; extract_archive "$IN" "$P/extract"
  else
    die "Path bukan file/folder biasa: $IN"
  fi

  [ -n "$OPT_NAME" ] && NAME="$OPT_NAME"
  NAME=$(sanitize_name "$NAME")
  [ -z "$(find "$ROOT" -type f -not -path '*/.git/*' -print -quit 2>/dev/null)" ] && die "Project kosong, tidak ada file untuk diupload."

  # --- cek repo di GitHub (hanya baca) ---
  info "Repository: $LOGIN/$NAME"
  api GET "https://api.github.com/repos/$LOGIN/$NAME"
  case "$CODE" in
    200) EXISTS=1
         DEFAULT_BR=$(json_get default_branch)
         REPO_PRIV=$(grep -o '"private": *\(true\|false\)' "$T/body" | head -1 | grep -o 'true\|false')
         warn "Repository sudah ada -> akan di-update (history lama aman, tanpa force push)."
         if [ "$VIS_SET" -eq 1 ] && { [ "$REPO_PRIV" = true ] && [ "$VIS" = public ] || [ "$REPO_PRIV" = false ] && [ "$VIS" = private ]; }; then
           warn "Visibilitas repo lama tidak diubah (saat ini: $([ "$REPO_PRIV" = true ] && echo private || echo public))."
         fi
         [ "$REPO_PRIV" = true ] && IS_PUBLIC=0 ;;
    404) [ "$VIS" = public ] || IS_PUBLIC=0 ;;
    401) die "Token ditolak. Jalankan: bash upload.sh --token" ;;
    000) die "Tidak ada koneksi internet." ;;
    *)   die "Gagal cek repo (HTTP $CODE): $(json_get message)" ;;
  esac

  BRANCH="${OPT_BRANCH:-${DEFAULT_BR:-main}}"
  git check-ref-format --branch "$BRANCH" >/dev/null 2>&1 || die "Nama branch tidak valid: $BRANCH"

  # --- siapkan git terpisah (folder project tidak disentuh) ---
  B64=$(printf '%s' "x-access-token:$TOKEN" | base64 | tr -d '\n')
  git --git-dir="$GITDIR" init -q 2>/dev/null || die "git init gagal."
  g config core.bare false
  g config core.fileMode false
  g config core.autocrlf false
  g config core.quotepath false
  g symbolic-ref HEAD "refs/heads/$BRANCH"
  g remote add origin "https://github.com/$LOGIN/$NAME.git"
  mkdir -p "$GITDIR/info"; : > "$GITDIR/info/exclude"

  # --- abaikan sampah umum bila project tak punya .gitignore ---
  local prune=()
  if [ "$ALL" -eq 0 ] && [ ! -f "$ROOT/.gitignore" ]; then
    printf '%s\n' 'node_modules/' '__pycache__/' '*.pyc' '.gradle/' '.DS_Store' 'Thumbs.db' >> "$GITDIR/info/exclude"
    prune=( \( -name node_modules -o -name .gradle -o -name __pycache__ \) -prune -o )
    info "Tanpa .gitignore: node_modules, __pycache__, .gradle otomatis diabaikan (pakai --all untuk ikut)."
  fi

  # --- lewati file > 100MB ---
  local big=0 f rel
  while IFS= read -r -d '' f; do
    rel="${f#"$ROOT"/}"; excl_add "$rel"; big=$((big+1))
    warn "Dilewati (>${MAX_FILE_MB}MB, batas GitHub): $rel"
  done < <(find "$ROOT" -path "$ROOT/.git" -prune -o "${prune[@]}" -type f -size +"${MAX_FILE_MB}"M -print0 2>/dev/null)

  local nested
  nested=$(find "$ROOT" -mindepth 2 -name .git -prune 2>/dev/null | wc -l | tr -d ' ')
  [ "$nested" -gt 0 ] && warn "Ada $nested repo git bersarang di dalam project; foldernya bisa tampil kosong di GitHub."

  # --- sambung ke history remote bila repo sudah berisi ---
  if [ "$EXISTS" -eq 1 ] && ga ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
    info "Mengambil history remote..."
    ga fetch -q origin "$BRANCH" || die "Gagal fetch dari remote."
    g reset -q "origin/$BRANCH" || die "Gagal sinkron dengan remote."
  fi

  info "Memindai & menambahkan file..."
  if [ "$NODEL" -eq 1 ]; then g add -A --ignore-removal >/dev/null 2>&1 || g add --ignore-removal . >/dev/null 2>&1 || die "git add gagal."
  else g add -A >/dev/null 2>&1 || die "git add gagal."; fi

  # --- pemindai rahasia (agar tidak diblokir GitHub push protection) ---
  local PAT='ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{50,}|gh[ousr]_[A-Za-z0-9]{36}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|xox[baprs]-[0-9A-Za-z-]{10,}|sk_live_[0-9A-Za-z]{20,}|-----BEGIN ([A-Z]+ )?PRIVATE KEY-----'
  : > "$P/sus.txt"
  ( cd "$ROOT" && g diff --cached --name-only -z | xargs -0 -r grep -IlE -e "$PAT" 2>/dev/null ) >> "$P/sus.txt" || true
  if [ "$IS_PUBLIC" -eq 1 ]; then
    g diff --cached --name-only | grep -E '(^|/)(\.env(\..*)?|id_rsa|id_ed25519|[^/]*\.(pem|key|jks|keystore))$' >> "$P/sus.txt" || true
  fi
  sort -u "$P/sus.txt" -o "$P/sus.txt"
  if [ -s "$P/sus.txt" ]; then
    warn "File berikut terindikasi berisi rahasia (token/key/.env):"
    head -20 "$P/sus.txt" | sed 's/^/      /'
    if confirm "Lewati file-file ini agar tidak bocor / tidak diblokir GitHub?"; then
      while IFS= read -r f; do
        [ -z "$f" ] && continue
        g rm -q --cached --ignore-unmatch -- "$f" >/dev/null 2>&1
        excl_add "$f"
      done < "$P/sus.txt"
      ok "File sensitif dilewati ($(wc -l < "$P/sus.txt" | tr -d ' ') file)."
    else
      warn "Dilanjutkan dengan file sensitif (GitHub bisa menolak push)."
    fi
  fi

  COUNT=$(g diff --cached --name-only | wc -l | tr -d ' ')
  if [ "$COUNT" -eq 0 ]; then
    ok "Tidak ada perubahan; repository sudah up-to-date."
    echo "https://github.com/$LOGIN/$NAME" > "$T/res.$idx"; return 0
  fi

  if [ "$DRY" -eq 1 ]; then
    info "[DRY-RUN] $COUNT file akan diupload ke $LOGIN/$NAME ($VIS), branch $BRANCH. Contoh:"
    g diff --cached --name-only | head -15 | sed 's/^/      /'
    [ "$COUNT" -gt 15 ] && echo "      ... dan $((COUNT-15)) file lainnya"
    ok "Dry-run selesai. Tidak ada yang dikirim."
    return 0
  fi

  if [ "$EXISTS" -eq 1 ]; then MSG="${OPT_MSG:-Update from Termux $(date '+%Y-%m-%d %H:%M')}"
  else MSG="${OPT_MSG:-Initial commit from Termux}"; fi
  g -c user.name="$LOGIN" -c user.email="$GH_EMAIL" commit -q -m "$MSG" >/dev/null 2>&1 || die "git commit gagal."
  ok "$COUNT file siap dikirim."

  # --- buat repo bila belum ada (tepat sebelum push) ---
  DESC=$(json_esc "${OPT_DESC:-Uploaded from Termux}")
  if [ "$EXISTS" -eq 0 ]; then
    info "Membuat repository baru ($VIS)..."
    api POST "https://api.github.com/user/repos" \
      "{\"name\":\"$NAME\",\"description\":\"$DESC\",\"private\":$PRIV,\"auto_init\":false}"
    case "$CODE" in
      201) ok "Repository dibuat." ;;
      401) die "Token ditolak saat membuat repo. Jalankan: bash upload.sh --token" ;;
      403) die "Token tidak punya izin membuat repo (butuh scope 'repo' / fine-grained: Administration write)." ;;
      422) die "GitHub menolak pembuatan repo: $(json_get message)" ;;
      000) die "Koneksi terputus." ;;
      *)   die "Gagal membuat repo (HTTP $CODE): $(json_get message)" ;;
    esac
  elif [ -n "$OPT_DESC" ]; then
    api PATCH "https://api.github.com/repos/$LOGIN/$NAME" "{\"description\":\"$DESC\"}"
    [ "$CODE" = 200 ] || warn "Gagal mengubah deskripsi (HTTP $CODE)."
  fi

  # --- push dengan retry untuk gangguan jaringan ---
  info "Mengirim ke GitHub..."
  local attempt=1 rc=1
  while [ $attempt -le 3 ]; do
    ga push --progress origin "HEAD:refs/heads/$BRANCH" 2>&1 | tee "$P/push.log"
    rc=${PIPESTATUS[0]}
    [ $rc -eq 0 ] && break
    if grep -qiE 'could not resolve|early EOF|RPC failed|timed out|connection (reset|refused)|unable to access|remote end hung up|HTTP 5[0-9][0-9]' "$P/push.log" \
       && ! grep -qiE 'GH013|push protection|authentication failed|403|401' "$P/push.log"; then
      warn "Gangguan jaringan (percobaan $attempt/3), mengulang..."
      sleep $((attempt*4)); attempt=$((attempt+1))
    else break; fi
  done
  if [ $rc -ne 0 ]; then
    if grep -qiE 'GH013|push protection' "$P/push.log"; then
      die "GitHub memblokir push karena ada rahasia (token/key) di file. Jalankan ulang dan jawab Y saat ditanya melewati file sensitif."
    elif grep -qiE 'authentication failed|invalid credentials|403|401|denied' "$P/push.log"; then
      die "Push ditolak. Token butuh izin tulis (scope 'repo' / Contents: write). Ganti token: bash upload.sh --token"
    elif grep -qiE 'GH001|large files|exceeds' "$P/push.log"; then
      die "Ada file terlalu besar untuk GitHub (>100MB)."
    fi
    die "Push gagal (lihat pesan di atas)."
  fi

  # --- verifikasi hasil di server ---
  local lsha rsha
  lsha=$(g rev-parse HEAD 2>/dev/null)
  rsha=$(ga ls-remote origin "refs/heads/$BRANCH" 2>/dev/null | cut -f1)
  if [ -n "$lsha" ] && [ "$lsha" = "$rsha" ]; then ok "Verifikasi: commit sudah ada di GitHub ($(printf '%.7s' "$lsha"))."
  else warn "Tidak bisa memverifikasi commit di server; cek manual di GitHub."; fi

  URL="https://github.com/$LOGIN/$NAME"
  ok "Selesai: $COUNT file, $((SECONDS-start)) detik$([ "$big" -gt 0 ] && echo ", $big file >${MAX_FILE_MB}MB dilewati")."
  printf '%s[>]%s %s\n' "$G" "$N" "$URL"
  echo "$URL" > "$T/res.$idx"
  return 0
}

# ================================ MAIN LOOP ==================================
fails=0; idx=0; total=${#inputs[@]}; urls=()
for p in "${inputs[@]}"; do
  idx=$((idx+1))
  printf '\n%s======== [%d/%d] %s ========%s\n' "$D" "$idx" "$total" "$(basename "$p")" "$N"
  ( process_project "$p" "$idx" )
  if [ $? -ne 0 ]; then fails=$((fails+1)); fi
  [ -s "$T/res.$idx" ] && urls+=("$(cat "$T/res.$idx")")
done

if [ "$total" -gt 1 ]; then
  echo; info "Ringkasan: $((total-fails)) berhasil, $fails gagal."
  for u in "${urls[@]}"; do echo "    $u"; done
fi

# notifikasi + salin link (butuh Termux:API; diabaikan jika tidak ada)
if [ ${#urls[@]} -gt 0 ] && [ "$DRY" -eq 0 ]; then
  last="${urls[${#urls[@]}-1]}"
  command -v termux-clipboard-set >/dev/null 2>&1 && ( timeout 5 termux-clipboard-set "$last" >/dev/null 2>&1 & )
  command -v termux-notification  >/dev/null 2>&1 && ( timeout 5 termux-notification --title "Upload GitHub selesai" --content "$last" --action "termux-open-url $last" >/dev/null 2>&1 & )
fi

[ "$fails" -eq 0 ] && exit 0 || exit 1