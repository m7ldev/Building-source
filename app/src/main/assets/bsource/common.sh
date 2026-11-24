# Building source — shared helpers (Ubuntu 24.04 ARM64 inside the app)
# Markers read by the app:  @@STAGE <percent> <text>   @@APK <path>   @@ERROR <text>   @@OK <text>   @@TARGET <t>

export DEBIAN_FRONTEND=noninteractive
export SDK="/root/android-sdk"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export TMPDIR="${TMPDIR:-/tmp}"
BIN="/opt/bsource/bin"
export PATH="$BIN:$PATH"
mkdir -p "$TMPDIR" "$SDK" "$BIN" /root/.bsource

CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
NDK_LINUX_API="https://api.github.com/repos/SnowNF/ndk-aarch64-linux/releases/latest"
NDK_LINUX_URL="https://github.com/SnowNF/ndk-aarch64-linux/releases/download/0.0.2/android-ndk-r29-linux-aarch64.tar.gz"
NDK_TERMUX_URL="https://github.com/lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.tar.xz"
SDKTOOLS_LINUX_API="https://api.github.com/repos/SnowNF/ndk-aarch64-linux/releases/tags/0.0.1"
SDKTOOLS_TERMUX_URL="https://github.com/lzhiyong/termux-ndk/releases/download/android-sdk/android-sdk-aarch64.tar.xz"

stage() { echo "@@STAGE $1 $2"; }
die()   { echo "@@ERROR $*"; exit 1; }
ok()    { echo "@@OK $*"; }

APT_DONE=0
apt_update() {
    [ "$APT_DONE" = 1 ] && return 0
    echo "› apt update"
    apt-get update -y 2>&1 | grep -vE '^(Get|Hit|Ign):' ; APT_DONE=1
}
pkg_has() { dpkg -s "$1" 2>/dev/null | grep -q '^Status: install ok installed'; }
pkg_get() {
    local need=()
    for p in "$@"; do pkg_has "$p" || need+=("$p"); done
    [ ${#need[@]} = 0 ] && return 0
    apt_update
    echo "› installing ${need[*]}"
    apt-get install -y --no-install-recommends "${need[@]}" 2>&1 | grep -vE '^(Get|Selecting|Preparing|Unpacking|Setting up|Processing)' | tail -n 25
    local rc=${PIPESTATUS[0]}
    if [ "$rc" != 0 ] && [ ${#need[@]} -gt 1 ]; then
        # one unknown name makes apt refuse the whole list → install them one by one
        rc=0
        for p in "${need[@]}"; do apt-get install -y --no-install-recommends "$p" >/dev/null 2>&1 || { echo "! package $p not available"; rc=1; }; done
    fi
    printf '%s\n' "${need[@]}" >> /root/.bsource/installed-pkgs
    return $rc
}
pkg_del() { apt-get remove -y "$@" 2>&1 | tail -n 3; true; }

java_home() { echo "/usr/lib/jvm/java-$1-openjdk-arm64"; }
any_java() {
    for v in 17 21; do [ -x "$(java_home $v)/bin/java" ] && { java_home $v; return 0; }; done
    return 1
}

gh_assets() {   # gh_assets api-url → every asset download url
    curl -fsSL "$1" 2>/dev/null | grep -o '"browser_download_url": *"[^"]*"' | sed 's/.*"\(http[^"]*\)"$/\1/'
}

fetch_extract() {   # fetch_extract url destdir
    local u="$1" d="$2" t="$TMPDIR/bs_dl_$$_$(basename "$u")"
    echo "› downloading $u"
    curl -fL --retry 3 -o "$t" "$u" 2>&1 | tail -n 1
    [ "${PIPESTATUS[0]}" = 0 ] && [ -s "$t" ] || { rm -f "$t"; echo "! download failed"; return 1; }
    mkdir -p "$d"
    local rc
    case "$u" in
        *.zip) unzip -q -o "$t" -d "$d"; rc=$? ;;
        *) tar -xf "$t" -C "$d" 2>"$TMPDIR/bs_tar_err_$$"; rc=$?
           if [ "$rc" != 0 ]; then
               # hard links cannot be made inside the app → extract again, copying each hard link as a normal file
               head -n 3 "$TMPDIR/bs_tar_err_$$"
               echo "› extracting again (hard links → copies)"
               pkg_get python3 >/dev/null
               python3 -I -c "$PY_UNTAR" "$t" "$d"; rc=$?
           fi
           rm -f "$TMPDIR/bs_tar_err_$$" ;;
    esac
    rm -f "$t"
    return $rc
}

PY_UNTAR='
import os, sys, shutil, tarfile
src, dst = sys.argv[1], os.path.abspath(sys.argv[2])
links = []
with tarfile.open(src) as tf:
    for m in tf:
        p = os.path.abspath(os.path.join(dst, m.name))
        if not (p == dst or p.startswith(dst + os.sep)):
            continue
        if m.islnk():
            links.append((m.linkname, p)); continue
        try:
            if m.isdir():
                os.makedirs(p, exist_ok=True)
            elif m.issym():
                os.makedirs(os.path.dirname(p), exist_ok=True)
                if os.path.lexists(p): os.remove(p)
                os.symlink(m.linkname, p)
            elif m.isfile():
                os.makedirs(os.path.dirname(p), exist_ok=True)
                if os.path.lexists(p): os.remove(p)
                with tf.extractfile(m) as f, open(p, "wb") as o:
                    shutil.copyfileobj(f, o, 1 << 20)
                os.chmod(p, m.mode & 0o7777)
        except OSError as e:
            print("! " + m.name + ": " + str(e))
for target, p in links:
    t = os.path.join(dst, target)
    try:
        os.makedirs(os.path.dirname(p), exist_ok=True)
        if os.path.lexists(p): os.remove(p)
        shutil.copy2(t, p)
    except OSError as e:
        print("! link " + p + ": " + str(e))
print("› extracted")
'


# ---------------------------------------------------------------- ARM versions of the SDK tools (aapt2 …)
# Google ships them only for x86 → take ARM builds and keep them in /opt/bsource/bin
ensure_aapt2() {
    [ -x "$BIN/aapt2" ] && "$BIN/aapt2" version >/dev/null 2>&1 && return 0
    pkg_get curl unzip xz-utils tar file >/dev/null
    local x="$TMPDIR/bs_sdktools" u
    for u in $(gh_assets "$SDKTOOLS_LINUX_API" | grep -iE '\.(tar\.gz|tar\.xz|zip)$') "$SDKTOOLS_TERMUX_URL"; do
        rm -rf "$x"; mkdir -p "$x"
        fetch_extract "$u" "$x" || continue
        local a; a=$(find "$x" -type f -name aapt2 | head -1)
        [ -n "$a" ] || continue
        for t in aapt2 aapt zipalign aidl dexdump split-select; do
            f=$(find "$x" -type f -name "$t" | head -1); [ -n "$f" ] && cp -f "$f" "$BIN/$t" && chmod +x "$BIN/$t"
        done
        if "$BIN/aapt2" version >/dev/null 2>&1; then echo "› aapt2 for ARM: $("$BIN/aapt2" version 2>&1 | head -1)"; rm -rf "$x"; return 0; fi
        echo "! this aapt2 build does not run here, trying another"
        rm -f "$BIN/aapt2"
    done
    rm -rf "$x"
    return 1
}

ensure_cmdline() {
    [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ] && return 0
    pkg_get curl unzip || return 1
    mkdir -p "$SDK/cmdline-tools"
    rm -rf "$SDK/cmdline-tools/latest" "$SDK/cmdline-tools/cmdline-tools"
    fetch_extract "$CMDLINE_URL" "$SDK/cmdline-tools" || return 1
    mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
    chmod +x "$SDK/cmdline-tools/latest/bin/"* 2>/dev/null
    [ -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]
}

sdkm() {
    local jh; jh="$(any_java)" || { pkg_get openjdk-17-jdk-headless; jh="$(java_home 17)"; }
    JAVA_HOME="$jh" "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "$@" 2>&1 | grep -vE '^\[=*|^$' ; return ${PIPESTATUS[0]}
}

licenses() { mkdir -p "$SDK/licenses"; yes 2>/dev/null | sdkm --licenses >/dev/null 2>&1; true; }

fix_buildtools() {
    for d in "$SDK"/build-tools/*/; do
        [ -d "$d" ] || continue
        for b in aapt aapt2 zipalign aidl dexdump split-select; do [ -x "$BIN/$b" ] && ln -sf "$BIN/$b" "$d$b"; done
    done
}

ensure_platform() {
    [ -f "$SDK/platforms/android-$1/android.jar" ] && return 0
    ensure_cmdline || return 1
    licenses
    echo "› installing Android SDK platform $1"
    sdkm "platforms;android-$1"
    [ -f "$SDK/platforms/android-$1/android.jar" ]
}

ensure_buildtools() {
    local v="${1:-35.0.0}"
    if [ ! -d "$SDK/build-tools/$v" ]; then
        ensure_cmdline || return 1
        licenses
        echo "› installing build-tools $v"
        sdkm "build-tools;$v"
    fi
    fix_buildtools
    [ -d "$SDK/build-tools/$v" ]
}

# ---------------------------------------------------------------- NDK for ARM64 Linux
ndk_dir() {
    for d in "$SDK"/ndk/*/; do
        d="${d%/}"
        [ -f "$d/source.properties" ] || continue
        [ -L "$d/toolchains" ] && continue
        echo "$d"; return 0
    done
    return 1
}

ensure_ndk() {
    [ -n "$(ndk_dir)" ] && return 0
    pkg_get curl unzip xz-utils tar || return 1
    local x="$TMPDIR/bs_ndk_x" u top
    for u in $(gh_assets "$NDK_LINUX_API" | grep -iE 'ndk.*(tar\.gz|tar\.xz|zip)$') "$NDK_LINUX_URL" "$NDK_TERMUX_URL"; do
        rm -rf "$x"; mkdir -p "$x"
        echo "› NDK (~350 MB)"
        fetch_extract "$u" "$x" || continue
        top=$(find "$x" -maxdepth 3 -name source.properties | head -1 | xargs -r dirname)
        [ -n "$top" ] || { echo "! unexpected NDK archive"; continue; }
        local rev; rev=$(grep -i 'Pkg.Revision' "$top/source.properties" | sed 's/.*= *//' | tr -d ' \r')
        [ -n "$rev" ] || rev="29.0.0"
        mkdir -p "$SDK/ndk"; rm -rf "$SDK/ndk/$rev"; mv "$top" "$SDK/ndk/$rev"; rm -rf "$x"
        # does its clang run here?
        local cl; cl=$(find "$SDK/ndk/$rev/toolchains/llvm/prebuilt" -maxdepth 3 -name clang -type f 2>/dev/null | head -1)
        if [ -z "$cl" ] || "$cl" --version >/dev/null 2>&1; then echo "› NDK $rev installed"; return 0; fi
        echo "! this NDK build does not run here, trying another"; rm -rf "$SDK/ndk/$rev"
    done
    rm -rf "$x"
    return 1
}

# make the ARM NDK work inside Ubuntu: Python that ndk-build expects, a linker whose libraries exist here
fix_ndk() {
    local nd; nd="$(ndk_dir)" || return 0
    [ -f "$nd/.bs-fixed-2" ] && return 0
    pkg_get python3 lld libxml2 file >/dev/null
    for pb in "$nd"/toolchains/llvm/prebuilt/*/; do
        [ -d "$pb" ] || continue
        # 1. python3 that ndk-build calls
        if [ ! -x "${pb}python3/bin/python3" ]; then
            mkdir -p "${pb}python3/bin"
            ln -sf /usr/bin/python3 "${pb}python3/bin/python3"
            ln -sf /usr/bin/python3 "${pb}python3/bin/python"
        fi
        # 2. programs whose libraries are missing here
        for b in "${pb}"bin/*; do
            [ -f "$b" ] && [ ! -L "$b" ] || continue
            file -b "$b" 2>/dev/null | grep -q ELF || continue
            miss=$(ldd "$b" 2>/dev/null | awk '/not found/{print $1}')
            [ -n "$miss" ] || continue
            n=$(basename "$b")
            case "$n" in
                ld.lld|lld|ld64.lld|wasm-ld|lld-link)
                    # Ubuntu's own ARM linker does the same job
                    mv -f "$b" "$b.ndk" && ln -sf /usr/bin/ld.lld "$b" && echo "› NDK $n → Ubuntu ld.lld ($miss missing)" ;;
                *)
                    for m in $miss; do
                        base="${m%%.so*}"
                        have=$(ls /usr/lib/aarch64-linux-gnu/"$base".so.* 2>/dev/null | head -1)
                        [ -n "$have" ] && [ ! -e "/usr/lib/aarch64-linux-gnu/$m" ] && ln -sf "$have" "/usr/lib/aarch64-linux-gnu/$m" && echo "› $m → $(basename "$have")"
                    done ;;
            esac
        done
    done
    [ -L "$nd"/toolchains/llvm/prebuilt/*/bin/ld.lld ] 2>/dev/null || true
    touch "$nd/.bs-fixed-2"
}

# the project asks for ndkVersion X → a folder of links to the installed NDK that reports version X
link_ndk() {
    local want="$1" have; have="$(ndk_dir)"
    [ -n "$want" ] && [ -n "$have" ] || return 0
    [ -e "$SDK/ndk/$want" ] && return 0
    mkdir -p "$SDK/ndk/$want"
    for e in "$have"/* "$have"/.[!.]*; do
        [ -e "$e" ] || continue
        [ "$(basename "$e")" = source.properties ] && continue
        ln -sfn "$e" "$SDK/ndk/$want/$(basename "$e")"
    done
    sed "s/^Pkg.Revision *=.*/Pkg.Revision = $want/" "$have/source.properties" > "$SDK/ndk/$want/source.properties"
    echo "› NDK $want → $(basename "$have")"
}

# ---------------------------------------------------------------- Gradle version that fits the project
agp_version() {
    local v
    v=$(grep -rhoE --include='*.gradle' --include='*.kts' 'com\.android\.tools\.build:gradle:[0-9][0-9.]*' . 2>/dev/null | grep -oE '[0-9][0-9.]*$' | sort -V | tail -1)
    [ -z "$v" ] && v=$(grep -rhoE --include='*.gradle' --include='*.kts' 'com\.android\.(application|library)["'"'"')[:space:]]+version[[:space:]("'"'"']+[0-9][0-9.]*' . 2>/dev/null | grep -oE '[0-9][0-9.]*$' | sort -V | tail -1)
    [ -z "$v" ] && [ -f gradle/libs.versions.toml ] && v=$(grep -iE '^[[:space:]]*(agp|android[-_]?gradle[-_]?plugin|androidGradlePlugin|android-plugin|gradleplugin|androidGradle)[[:space:]]*=' gradle/libs.versions.toml | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1)
    echo "$v"
}

wrapper_version() {
    [ -f gradle/wrapper/gradle-wrapper.properties ] || return 0
    grep -E '^distributionUrl' gradle/wrapper/gradle-wrapper.properties | grep -oE 'gradle-[0-9][0-9.]*(-rc-[0-9]+)?' | head -1 | sed 's/^gradle-//'
}

gradle_for_agp() {
    local a="$1" major minor
    major=$(echo "$a" | cut -d. -f1); minor=$(echo "$a" | cut -d. -f2)
    case "$major" in
        9) echo "9.2.1" ;;
        8) if [ "${minor:-0}" -ge 11 ] 2>/dev/null; then echo "8.14.3"; else echo "8.13"; fi ;;
        7) if [ "${minor:-0}" -ge 3 ] 2>/dev/null; then echo "7.6.4"; else echo "7.5.1"; fi ;;
        4|3) echo "6.9.4" ;;
        *) echo "8.13" ;;
    esac
}

ensure_gradle_dist() {
    local v="$1" base="/root/.bsource/gradle"
    local g="$base/gradle-$v/bin/gradle"
    if [ ! -x "$g" ]; then
        mkdir -p "$base"
        fetch_extract "https://services.gradle.org/distributions/gradle-$v-bin.zip" "$base" >&2 || return 1
        chmod +x "$g" 2>/dev/null
    fi
    [ -x "$g" ] && echo "$g"
}
