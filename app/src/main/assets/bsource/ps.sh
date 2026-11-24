# Building source — PlayStation homebrew + plain C/C++ (Make / CMake / Meson) projects
# Sourced by build.sh after common.sh. Every SDK / toolchain is fetched the first time it is needed.

TC="$HOME/.bsource/toolchains"
SRC="$TC/src"
# few parallel jobs: Android kills apps that start too many processes (and phones run out of RAM)
NCPU="$(nproc 2>/dev/null || echo 4)"
if [ "${BS_JOBS:-0}" -gt 0 ] 2>/dev/null; then JOBS="$BS_JOBS"; else JOBS=$(( NCPU < 4 ? NCPU : 4 )); fi
TJOBS=$(( JOBS < 3 ? JOBS : 3 ))   # for toolchains built from source
mkdir -p "$TC" "$SRC"

# ---------------------------------------------------------------- which kind of project is this?
detect_target() {
    local d="$1"
    X=(-not -path '*/node_modules/*' -not -path '*/.git/*' -not -path '*/build/*' -not -path '*/target/*')
    f() { find "$d" -maxdepth 4 \( "$@" \) "${X[@]}" 2>/dev/null | head -1 | grep -q .; }
    g() { grep -rqsE --include='Makefile' --include='makefile' --include='GNUmakefile' --include='Makefile.*' --include='*.mk' --include='CMakeLists.txt' \
            --include='*.cmake' --include='CMakePresets.json' --include='meson.build' \
            --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=build "$1" "$d" 2>/dev/null; }
    gradle_android() { grep -rqsE --include='*.gradle' --include='*.gradle.kts' --include='*.toml' \
            --exclude-dir=node_modules --exclude-dir=.git --exclude-dir=build \
            'com\.android\.(application|library)|android[[:space:]]*\{|libs\.plugins\.android' "$d" 2>/dev/null; }
    if f -name pubspec.yaml && grep -rqs --include=pubspec.yaml 'flutter' "$d"; then echo flutter
    elif f -name package.json && grep -rqs --include=package.json --exclude-dir=node_modules '"react-native"' "$d" && f -name settings.gradle -o -name settings.gradle.kts; then echo reactnative
    elif gradle_android; then echo android
    elif g '\bPS5_PAYLOAD_SDK\b|prospero-(clang|cmake)'; then echo ps5
    elif g '\bOO_PS4_TOOLCHAIN\b|OpenOrbis|\bPS4_PAYLOAD_SDK\b'; then echo ps4
    elif g '\bPSL1GHT\b|\bPS3DEV\b|ppu-gcc|ppu-lv2'; then echo ps3
    elif g '\bPS2SDK\b|\bPS2DEV\b|mips64r5900el|\bee-gcc\b'; then echo ps2
    elif g '\bPSN00BSDK\b|PSn00bSDK|psn00bsdk|libpsn00b'; then echo ps1
    elif f -name Cargo.toml; then echo rust
    elif f -name build.zig; then echo zig
    elif f -name '*.nimble'; then echo nim
    elif f -name '*.cabal' -o -name stack.yaml; then echo haskell
    elif f -name pubspec.yaml; then echo dart
    elif f -name go.mod; then echo go
    elif f -name '*.sln' -o -name '*.slnx' -o -name '*.csproj' -o -name '*.fsproj' -o -name '*.vbproj'; then echo dotnet
    elif f -name pom.xml; then echo maven
    elif f -name settings.gradle -o -name settings.gradle.kts -o -name build.gradle -o -name build.gradle.kts; then echo gradlejava
    elif [ -z "$(find "$d" -maxdepth 1 \( -name CMakeLists.txt -o -name Makefile -o -name makefile -o -name meson.build -o -name configure \) | head -1)" ] \
         && [ -n "$(find "$d" -maxdepth 2 -type f \( -name '*.sh' -o -name build.py -o -name justfile -o -name xmake.lua -o -name build.ninja \) -not -path '*/.git/*' | head -1)" ] \
         && [ -n "$(find_build_script "$d")" ]; then echo script
    elif f -name build.xml; then echo ant
    elif f -name CMakeLists.txt -o -name Makefile -o -name makefile -o -name GNUmakefile -o -name meson.build -o -name configure \
           -o -name configure.ac -o -name autogen.sh -o -name SConstruct -o -name '*.pro'; then echo native
    elif f -name package.json; then echo node
    elif f -name pyproject.toml -o -name setup.py -o -name requirements.txt; then echo python
    elif f -name '*.c' -o -name '*.cpp' -o -name '*.cc' -o -name '*.cxx'; then echo native
    elif f -name '*.java'; then echo java
    elif f -name '*.py'; then echo python
    elif [ -n "$(find_build_script "$d")" ]; then echo script
    else echo unknown
    fi
}

# shallowest folder containing one of the given file names
root_with() {
    local d="$1"; shift
    local expr=() first=1
    for n in "$@"; do [ $first = 1 ] && first=0 || expr+=(-o); expr+=(-name "$n"); done
    find "$d" -maxdepth 5 \( "${expr[@]}" \) -not -path '*/build/*' 2>/dev/null \
        | awk '{ print gsub("/","/"), $0 }' | sort -n | head -1 | cut -d' ' -f2- | xargs -r -d '\n' dirname
}

# clang-15 / clang-18 … names some SDKs ask for → Termux's clang
clang_shims() {
    mkdir -p "$TC/bin"
    for v in 13 14 15 16 17 18 19 20 21; do
        [ -x "/usr/bin/clang-$v" ] && continue          # the real one exists
        ln -sf /usr/bin/clang "$TC/bin/clang-$v"
        ln -sf /usr/bin/clang++ "$TC/bin/clang++-$v"
        [ -x /usr/bin/ld.lld ] && ln -sf /usr/bin/ld.lld "$TC/bin/ld.lld-$v"
        [ -x /usr/bin/lld ] && ln -sf /usr/bin/lld "$TC/bin/lld-$v"
        for t in llvm-ar llvm-strip llvm-objcopy llvm-nm llvm-ranlib llvm-readelf llvm-objdump; do
            [ -x "/usr/bin/$t" ] && ln -sf "/usr/bin/$t" "$TC/bin/$t-$v"
        done
    done
    export PATH="$TC/bin:$PATH"
}

gh_asset() {   # gh_asset owner/repo regex  → first release asset url matching regex
    curl -fsSL "https://api.github.com/repos/$1/releases/latest" 2>/dev/null \
        | grep -o '"browser_download_url": *"[^"]*"' | sed 's/.*"\(http[^"]*\)"$/\1/' | grep -iE "$2" | head -1
}


host_build_deps() {
    stage 6 "Installing compiler tools (first time only)"
    pkg_get build-essential git make cmake ninja-build autoconf automake libtool bison flex texinfo patch wget \
            python3 pkg-config libgmp-dev libmpfr-dev libmpc-dev zlib1g-dev libelf-dev libncurses-dev libssl-dev \
            gettext libisl-dev libusb-dev libreadline-dev 2>&1 | tail -n 40
}

# ---------------------------------------------------------------- PS5 (ps5-payload-dev SDK, clang + lld)
ensure_ps5() {
    stage 10 "PS5 payload SDK"
    pkg_get clang lld llvm make cmake pkg-config curl unzip python3 || return 1
    clang_shims
    local d="$TC/ps5-payload-sdk"
    if [ ! -d "$d/toolchain" ] && [ ! -d "$d/target" ]; then
        rm -rf "$d" "$TC/_ps5"
        fetch_extract "https://github.com/ps5-payload-dev/sdk/releases/latest/download/ps5-payload-sdk.zip" "$TC/_ps5" || return 1
        local top; top=$(find "$TC/_ps5" -maxdepth 3 -type d \( -name toolchain -o -name target \) | head -1 | xargs -r dirname)
        [ -n "$top" ] || { echo "! unexpected PS5 SDK archive"; return 1; }
        mv "$top" "$d"; rm -rf "$TC/_ps5"
    fi
    export PS5_PAYLOAD_SDK="$d"
    echo "› PS5_PAYLOAD_SDK=$d"
}

build_ps5() {
    ensure_ps5 || die "Could not set up the PS5 payload SDK"
    stage 30 "Compiling for PS5"
    if [ -f CMakeLists.txt ] && [ -x "$PS5_PAYLOAD_SDK/bin/prospero-cmake" ]; then
        "$PS5_PAYLOAD_SDK/bin/prospero-cmake" -S . -B build 2>&1 && cmake --build build -j"$JOBS" 2>&1
    else
        ps_make 'PS5_PAYLOAD_SDK|prospero' PS5_PAYLOAD_SDK="$PS5_PAYLOAD_SDK"
    fi
}

# ---------------------------------------------------------------- PS4 (OpenOrbis toolchain, clang + lld)
ensure_ps4() {
    stage 10 "OpenOrbis PS4 toolchain"
    pkg_get clang lld llvm make cmake git golang-go curl unzip python3 || return 1
    clang_shims
    local d="$TC/OpenOrbis"
    if [ ! -f "$d/link.x" ] || [ ! -d "$d/include/orbis" ]; then
        rm -rf "$d" "$TC/_oo" 2>/dev/null
        local u; u=$(gh_asset OpenOrbis/OpenOrbis-PS4-Toolchain 'toolchain.*\.(tar\.gz|zip)$')
        [ -n "$u" ] || u="https://github.com/OpenOrbis/OpenOrbis-PS4-Toolchain/releases/download/v0.5.4/toolchain-llvm-18.tar.gz"
        fetch_extract "$u" "$TC/_oo" || return 1
        local top; top=$(find "$TC/_oo" -maxdepth 4 -name link.x | head -1 | xargs -r dirname)
        [ -n "$top" ] || { echo "! unexpected OpenOrbis archive"; return 1; }
        mv "$top" "$d"; rm -rf "$TC/_oo" 2>/dev/null
    fi
    export OO_PS4_TOOLCHAIN="$d"
    local L="$d/bin/linux"
    mkdir -p "$L"
    # the release tools are x86 programs → rebuild them for ARM
    export GOFLAGS="-buildvcs=false" GOTOOLCHAIN=auto
    for tool in create-fself create-gp4; do
        [ -f "$L/.$tool-arm" ] && continue
        echo "› building $tool for ARM"
        rm -rf "$SRC/$tool"
        git clone --depth 1 "https://github.com/OpenOrbis/$tool" "$SRC/$tool" 2>&1 | tail -n 1
        local c="$SRC/$tool/cmd/$tool"; [ -d "$c" ] || c="$SRC/$tool"
        local mf=""; [ -f "$c/go-linux.mod" ] && mf="-modfile=go-linux.mod"
        mv -f "$L/$tool" "$L/$tool.x86" 2>/dev/null
        ( cd "$c" && go build $mf -o "$L/$tool" . 2>&1 ) && touch "$L/.$tool-arm" || echo "! $tool could not be built"
    done
    # PKG: PkgTool.Core is an x86 .NET program → build it from LibOrbisPkg for ARM
    if [ ! -f "$L/.pkgtool-arm" ]; then
        echo "› building PkgTool.Core for ARM (.NET 8)"
        export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_EnableWriteXorExecute=0
        command -v dotnet >/dev/null 2>&1 || pkg_get dotnet-sdk-8.0 >/dev/null 2>&1 || ensure_dotnet 8.0
        if command -v dotnet >/dev/null 2>&1; then
            rm -rf "$SRC/LibOrbisPkg"
            git clone --depth 1 https://github.com/OpenOrbis/LibOrbisPkg "$SRC/LibOrbisPkg" 2>&1 | tail -n 1
            if ( cd "$SRC/LibOrbisPkg" && dotnet publish PkgTool.Core/PkgTool.Core.csproj -c Release -r linux-arm64 \
                    --self-contained false -o "$L/pkgtool-arm" 2>&1 | tail -n 15 ) && [ -f "$L/pkgtool-arm/PkgTool.Core.dll" ]; then
                mv -f "$L/PkgTool.Core" "$L/PkgTool.Core.x86" 2>/dev/null
                printf '#!/bin/bash\nexport DOTNET_EnableWriteXorExecute=0 DOTNET_CLI_TELEMETRY_OPTOUT=1\nexec dotnet "%s/pkgtool-arm/PkgTool.Core.dll" "$@"\n' "$L" > "$L/PkgTool.Core"
                chmod +x "$L/PkgTool.Core" && touch "$L/.pkgtool-arm"
            else
                echo "! PkgTool.Core could not be built → the ELF / eboot.bin are built, the .pkg step will fail"
            fi
        else
            echo "! .NET could not be installed → the .pkg step will fail"
        fi
    fi
    chmod +x "$L"/* 2>/dev/null
    export PATH="$L:$PATH"
    echo "› OO_PS4_TOOLCHAIN=$d"
}

# the makefile that uses the given SDK variable (also Makefile.ps4, Makefile.ps4.dos …)
sdk_makefile() {   # sdk_makefile dir regex
    grep -rlsE --include='Makefile' --include='makefile' --include='GNUmakefile' --include='Makefile.*' --include='*.mk' \
        --exclude-dir=.git --exclude-dir=node_modules "$2" "$1" 2>/dev/null | grep -v '\.mk$' \
        | awk '{ print gsub("/","/"), $0 }' | sort -n | head -1 | cut -d' ' -f2-
}

# absolute paths from the author's computer (/home/…/make_gp4.py) → the same file inside the project
fix_abs_paths() {   # fix_abs_paths makefile projectroot
    python3 -I - "$1" "$2" <<'PY'
import os, re, sys
mf, root = sys.argv[1], sys.argv[2]
s = open(mf, encoding='utf-8', errors='surrogateescape').read()
names = {}
for d, ds, fs in os.walk(root):
    ds[:] = [x for x in ds if x not in ('.git', 'node_modules')]
    for f in fs: names.setdefault(f, os.path.join(d, f))
def fix(m):
    p = m.group(0)
    if os.path.exists(p): return p
    b = os.path.basename(p)
    if b in names:
        print('› path fixed: %s → %s' % (p, names[b])); return names[b]
    return p
t = re.sub(r'(?<![\w$])/(?:home|Users|root|mnt|media|opt)/[^\s\'"`)]+', fix, s)
if t != s: open(mf, 'w', encoding='utf-8', errors='surrogateescape').write(t)
PY
}

# build a console project: the makefile that uses the SDK (any name: Makefile, Makefile.ps5 …), else a build script, else make
ps_make() {   # ps_make sdk-regex [make VAR=value …]
    local re="$1"; shift
    local mf; mf=$(sdk_makefile "$dest" "$re")
    if [ -n "$mf" ]; then
        cd "$(dirname "$mf")" || return 1
        fix_abs_paths "$mf" "$dest"
        local t=""; grep -qE '^pkg[[:space:]]*:' "$mf" && t="pkg"
        echo "› make -f $(basename "$mf") $t"
        make -j"$JOBS" -f "$(basename "$mf")" "$@" $t 2>&1
    elif [ -f Makefile ] || [ -f makefile ] || [ -f GNUmakefile ]; then
        make -j"$JOBS" "$@" 2>&1
    else
        build_script "$dest"; local r=$?
        [ "$r" = 2 ] && echo "! no Makefile or build script found for this console project"
        return $r
    fi
}

build_ps4() {
    ensure_ps4 || die "Could not set up the OpenOrbis PS4 toolchain"
    stage 30 "Compiling for PS4"
    local mf; mf=$(sdk_makefile "$dest" 'OO_PS4_TOOLCHAIN|OpenOrbis')
    if [ -n "$mf" ]; then
        cd "$(dirname "$mf")" || return 1
        fix_abs_paths "$mf" "$dest"
        local t="all"; grep -qE '^pkg[[:space:]]*:' "$mf" && t="pkg"
        echo "› make -f $(basename "$mf") $t"
        make -j"$JOBS" -f "$(basename "$mf")" OO_PS4_TOOLCHAIN="$OO_PS4_TOOLCHAIN" $t 2>&1
    elif [ -f CMakeLists.txt ]; then
        cmake -S . -B build -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ 2>&1 && cmake --build build -j"$JOBS" 2>&1
    else
        ps_make 'OO_PS4_TOOLCHAIN|OpenOrbis' OO_PS4_TOOLCHAIN="$OO_PS4_TOOLCHAIN"
    fi
}

# ---------------------------------------------------------------- PS3 (ps3toolchain + PSL1GHT, built from source)
ensure_ps3() {
    export PS3DEV="$TC/ps3dev" PSL1GHT="$TC/ps3dev"
    export PATH="$PS3DEV/bin:$PS3DEV/ppu/bin:$PS3DEV/spu/bin:$PATH"
    [ -x "$PS3DEV/ppu/bin/ppu-gcc" ] && [ -d "$PSL1GHT/ppu/include" ] && return 0
    host_build_deps
    stage 10 "Building the PS3 toolchain from source (first time: can take hours)"
    rm -rf "$SRC/ps3toolchain"
    git clone --depth 1 https://github.com/ps3dev/ps3toolchain "$SRC/ps3toolchain" 2>&1 | tail -2 || return 1
    ( cd "$SRC/ps3toolchain" && MAKEFLAGS="-j$TJOBS" ./toolchain.sh 2>&1 )
    [ -x "$PS3DEV/ppu/bin/ppu-gcc" ]
}

build_ps3() {
    ensure_ps3 || die "The PS3 toolchain could not be built on this phone — see the log"
    stage 40 "Compiling for PS3"
    ps_make 'PSL1GHT|PS3DEV|ppu-gcc|ppu-lv2'
}

# ---------------------------------------------------------------- PS2 (ps2dev, built from source)
ensure_ps2() {
    export PS2DEV="$TC/ps2dev" PS2SDK="$TC/ps2dev/ps2sdk" GSKIT="$TC/ps2dev/gsKit"
    export PATH="$PS2DEV/bin:$PS2DEV/ee/bin:$PS2DEV/iop/bin:$PS2DEV/dvp/bin:$PS2SDK/bin:$PATH"
    # already set up? only if the compiler really runs (an old broken copy is replaced)
    local c0; c0=$(ls "$PS2DEV"/ee/bin/*-ps2-elf-gcc 2>/dev/null | head -1)
    [ -n "$c0" ] && "$c0" --version >/dev/null 2>&1 && [ -d "$PS2SDK/ee/include" ] && return 0
    [ -n "$c0" ] && echo "› the PS2 toolchain here is broken → downloading it again"
    # 1) ready-made ARM64 build for Ubuntu 24.04 from ps2dev (minutes instead of hours)
    stage 10 "PS2 toolchain (ready-made ARM64 build)"
    pkg_get make git curl tar libmpc3 libmpfr6 libgmp10 libisl23 libzstd1 zlib1g >/dev/null
    local u; u=$(gh_asset ps2dev/ps2dev 'ubuntu-24\.04-arm\.tar\.gz$')
    [ -n "$u" ] || u="https://github.com/ps2dev/ps2dev/releases/download/v2.0.0/ps2dev-ubuntu-24.04-arm.tar.gz"
    # extract straight into its final place: inside the app, hard links (gcc, g++ …) only work if the files are never moved
    rm -rf "$PS2DEV" "$TC"/_ps2 "$TC"/_ps2_* 2>/dev/null
    fetch_extract "$u" "$TC" || echo "! extracting the PS2 toolchain failed"
    local cc; cc=$(ls "$PS2DEV"/ee/bin/*-ps2-elf-gcc 2>/dev/null | head -1)
    echo "› compiler: ${cc:-not found}"
    if [ -n "$cc" ]; then
        "$cc" --version 2>&1 | head -n 2
        if "$cc" --version >/dev/null 2>&1; then echo "› PS2DEV=$PS2DEV"; return 0; fi
        ls -la "$cc" 2>&1; local real; real=$(readlink -f "$cc"); echo "› real file: $real"; ls -la "$real" 2>&1
        head -c 20 "$real" 2>/dev/null | od -c | head -n 2
    fi
    # building it from source on a phone gets stopped by Android after a few minutes, so only do it when asked
    if [ "${BS_PS2_SOURCE:-0}" != 1 ]; then
        echo "! the ready-made PS2 toolchain does not run here — send this log"
        return 1
    fi
    echo "! ready-made build did not work here → building from source"
    # 2) from source
    host_build_deps
    stage 12 "Building the PS2 toolchain from source (first time: can take hours)"
    rm -rf "$SRC/ps2dev" 2>/dev/null
    git clone --depth 1 https://github.com/ps2dev/ps2dev "$SRC/ps2dev" 2>&1 | tail -2 || return 1
    ( cd "$SRC/ps2dev" && MAKEFLAGS="-j$TJOBS" ./build-all.sh 2>&1 )
    ls "$PS2DEV"/ee/bin/*-gcc >/dev/null 2>&1
}

build_ps2() {
    ensure_ps2 || die "The PS2 toolchain could not be set up — see the log"
    stage 40 "Compiling for PS2"
    ps_make 'PS2SDK|PS2DEV|mips64r5900el|ee-gcc'
}

# ---------------------------------------------------------------- PS1 (mipsel GCC + PSn00bSDK, built from source)
PS1_BINUTILS=2.43.1
PS1_GCC=14.2.0
ensure_ps1() {
    local tcdir="$TC/mipsel-none-elf" sdk="$TC/psn00bsdk"
    export PATH="$tcdir/bin:$PATH" PSN00BSDK_TC="$tcdir" PSN00BSDK_LIBS="$sdk/lib/libpsn00b"
    [ -x "$tcdir/bin/mipsel-none-elf-gcc" ] && [ -d "$sdk/lib/libpsn00b" ] && return 0
    host_build_deps
    local b="$SRC/ps1"
    mkdir -p "$b"
    if [ ! -x "$tcdir/bin/mipsel-none-elf-gcc" ]; then
        stage 10 "Building the PS1 compiler from source (first time: can take hours)"
        ( cd "$b" && rm -rf binutils-* gcc-* build-* \
          && fetch_extract "https://ftpmirror.gnu.org/gnu/binutils/binutils-$PS1_BINUTILS.tar.xz" "$b" \
          && mkdir build-binutils && cd build-binutils \
          && ../binutils-$PS1_BINUTILS/configure --prefix="$tcdir" --target=mipsel-none-elf --disable-docs --disable-nls \
               --disable-werror --with-float=soft MAKEINFO=true 2>&1 \
          && make -j"$TJOBS" MAKEINFO=true 2>&1 && make install-strip MAKEINFO=true 2>&1 ) || return 1
        ( cd "$b" && fetch_extract "https://ftpmirror.gnu.org/gnu/gcc/gcc-$PS1_GCC/gcc-$PS1_GCC.tar.xz" "$b" \
          && mkdir build-gcc && cd build-gcc \
          && ../gcc-$PS1_GCC/configure --prefix="$tcdir" --target=mipsel-none-elf --disable-docs --disable-nls \
               --disable-werror --disable-libada --disable-libssp --disable-libquadmath --disable-libstdc++-v3 \
               --with-float=soft --enable-languages=c,c++ --without-isl --without-headers --with-gnu-as --with-gnu-ld MAKEINFO=true 2>&1 \
          && make -j"$TJOBS" MAKEINFO=true all-gcc all-target-libgcc 2>&1 \
          && make MAKEINFO=true install-strip-gcc install-strip-target-libgcc 2>&1 ) || return 1
    fi
    if [ ! -d "$sdk/lib/libpsn00b" ]; then
        stage 20 "Building PSn00bSDK"
        rm -rf "$SRC/PSn00bSDK"
        git clone --depth 1 --recursive https://github.com/Lameguy64/PSn00bSDK "$SRC/PSn00bSDK" 2>&1 | tail -2 || return 1
        ( cd "$SRC/PSn00bSDK" && cmake --preset default -DCMAKE_INSTALL_PREFIX="$sdk" -DPSN00BSDK_TC="$tcdir" 2>&1 \
          && cmake --build ./build -j"$JOBS" 2>&1 && cmake --install ./build 2>&1 ) || return 1
    fi
    [ -d "$sdk/lib/libpsn00b" ]
}

build_ps1() {
    ensure_ps1 || die "The PS1 toolchain could not be built on this phone — see the log"
    stage 40 "Compiling for PS1"
    if [ -f CMakePresets.json ]; then
        cmake --preset default 2>&1 && cmake --build ./build -j"$JOBS" 2>&1
    elif [ -f CMakeLists.txt ]; then
        cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE="$PSN00BSDK_LIBS/cmake/sdk.cmake" 2>&1 && cmake --build build -j"$JOBS" 2>&1
    else
        ps_make 'PSN00BSDK|PSn00bSDK|psn00bsdk|libpsn00b'
    fi
}

# ---------------------------------------------------------------- plain C / C++ (runs on the phone)
build_native() {
    stage 10 "C / C++ tools"
    pkg_get build-essential clang make cmake pkg-config || die "Could not install the compiler"
    stage 30 "Compiling"
    if [ -f CMakeLists.txt ]; then
        pkg_get ninja-build >/dev/null
        cmake -S . -B build -DCMAKE_BUILD_TYPE=Release 2>&1 && cmake --build build -j"$JOBS" 2>&1
    elif [ -f meson.build ]; then
        pkg_get meson ninja-build || die "Could not install meson"
        meson setup build 2>&1 && ninja -C build -j "$JOBS" 2>&1
    elif [ -f configure ]; then
        sh ./configure 2>&1 && make -j"$JOBS" 2>&1
    elif [ -f autogen.sh ] || [ -f configure.ac ] || [ -f configure.in ]; then
        pkg_get autoconf automake libtool autopoint gettext >/dev/null
        { [ -f autogen.sh ] && NOCONFIGURE=1 sh ./autogen.sh 2>&1 || autoreconf -fi 2>&1; } && sh ./configure 2>&1 && make -j"$JOBS" 2>&1
    elif [ -f Makefile ] || [ -f makefile ] || [ -f GNUmakefile ]; then
        make -j"$JOBS" 2>&1
    elif [ -f SConstruct ]; then
        pkg_get scons >/dev/null && scons -j"$JOBS" 2>&1
    elif ls ./*.pro >/dev/null 2>&1; then
        pkg_get qtbase5-dev qt5-qmake >/dev/null && qmake 2>&1 && make -j"$JOBS" 2>&1
    else
        # no build system: compile every source file into one program
        local cs cps; cs=$(find . -name '*.c' -not -path '*/test*' | tr '\n' ' '); cps=$(find . \( -name '*.cpp' -o -name '*.cc' -o -name '*.cxx' \) -not -path '*/test*' | tr '\n' ' ')
        [ -n "$cs$cps" ] || die "No source files found"
        mkdir -p bsout
        if [ -n "$cps" ]; then
            # shellcheck disable=SC2086
            g++ -O2 -I. -Iinclude -o "bsout/$BS_NAME" $cps $cs -lm -lpthread 2>&1
        else
            # shellcheck disable=SC2086
            gcc -O2 -I. -Iinclude -o "bsout/$BS_NAME" $cs -lm -lpthread 2>&1
        fi
    fi
}

# ---------------------------------------------------------------- results
collect_outputs() {   # collect_outputs marker target outdir
    local marker="$1" target="$2" out="$3" n=0 pat
    case "$target" in
        ps1) pat='.*\.(exe|elf|bin|cue|iso)$' ;;
        ps2) pat='.*\.(elf|irx|iso)$' ;;
        ps3) pat='.*\.(elf|self|fake\.self|pkg|sprx|prx)$' ;;
        ps4) pat='.*\.(elf|oelf|bin|self|prx|sprx|pkg)$' ;;
        ps5) pat='.*\.(elf|bin|self|prx|sprx)$' ;;
        any) pat='.*\.(apk|aab|pkg|elf|self|prx|sprx|irx|iso|exe|jar|nro|nsp|vpk|3dsx|cia|wasm)$' ;;
        *)   pat='' ;;
    esac
    while IFS= read -r f; do
        case "$f" in *CMakeFiles*|*/.git/*|*.o|*.a) continue ;; esac
        case "$f" in */sce_module/*|*/sce_sys/*) continue ;; esac   # copies of system files in the package folder
        if [ -z "$pat" ]; then
            # native: executables and shared libraries
            [ -x "$f" ] || case "$f" in *.so) ;; *) continue ;; esac
            case "$f" in *.sh|*.py|*.cmake|*/configure|*config.status) continue ;; esac
            head -c 4 "$f" 2>/dev/null | grep -q 'ELF' || continue
        fi
        mkdir -p "$out"
        cp -f "$f" "$out/" && { echo "@@APK $out/$(basename "$f")"; n=$((n+1)); }
    done < <(if [ -n "$pat" ]; then find . -type f -newer "$marker" -regextype posix-extended -iregex "$pat" 2>/dev/null;
             else find . -type f -newer "$marker" 2>/dev/null; fi)
    echo "$n" > "$TMPDIR/bs_count_$$"
}
