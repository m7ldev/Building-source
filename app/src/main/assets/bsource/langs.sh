# Building source — every other kind of source project (Rust, Go, Node, Python, Java, .NET, Flutter …)
# Sourced by build.sh / libs.sh after common.sh and ps.sh. Each tool is downloaded the first time it is needed.

BS_N=0
emit() {   # emit file  → copy to the output folder and report it to the app
    [ -e "$1" ] || return 0
    mkdir -p "$out"
    if [ -d "$1" ]; then
        local z="$out/$(basename "$1").zip"
        (cd "$(dirname "$1")" && zip -qr "$z" "$(basename "$1")") && { echo "@@APK $z"; BS_N=$((BS_N+1)); echo "$z" >> "$TMPDIR/bs_emit_$$"; }
    else
        cp -f "$1" "$out/" && { echo "@@APK $out/$(basename "$1")"; BS_N=$((BS_N+1)); echo "$1" >> "$TMPDIR/bs_emit_$$"; }
    fi
}
emit_new() {   # emit_new dir regex  → every matching file newer than the marker
    local f
    while IFS= read -r f; do emit "$f"; done < <(find "$1" -type f -newer "$marker" -regextype posix-extended -iregex "$2" \
        -not -path '*/node_modules/*' -not -path '*/.git/*' 2>/dev/null)
}
first_file() {   # first_file dir name...  → shallowest match
    local d="$1"; shift
    local expr=() first=1
    for n in "$@"; do [ $first = 1 ] && first=0 || expr+=(-o); expr+=(-name "$n"); done
    find "$d" -maxdepth 4 \( "${expr[@]}" \) -not -path '*/node_modules/*' -not -path '*/.git/*' -not -path '*/build/*' \
        -not -path '*/target/*' 2>/dev/null | awk '{ print gsub("/","/"), $0 }' | sort -n | head -1 | cut -d' ' -f2-
}
root_of() { local f; f=$(first_file "$@"); [ -n "$f" ] && dirname "$f"; }

# ---------------------------------------------------------------- Rust (cargo)
ensure_rust() {
    export PATH="/root/.cargo/bin:$PATH"
    command -v cargo >/dev/null 2>&1 && return 0
    stage 10 "Rust (rustup)"
    pkg_get build-essential curl pkg-config libssl-dev ca-certificates >/dev/null
    curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --no-modify-path 2>&1 | tail -n 5
    command -v cargo >/dev/null 2>&1 || pkg_get cargo rustc
}
build_rust() {
    ensure_rust || die "Could not install Rust"
    cd "$(root_of "$1" Cargo.toml)" || die "Cargo.toml not found"
    stage 30 "cargo build --release"
    pkg_get build-essential pkg-config libssl-dev >/dev/null
    cargo build --release -j "$JOBS" 2>&1 || return 1
    local f
    for f in target/release/*; do
        [ -f "$f" ] && [ -x "$f" ] && head -c 4 "$f" | grep -q ELF && emit "$f"
    done
    emit_new target/release '.*/target/release/[^/]*\.so$'; true
}

# ---------------------------------------------------------------- Go
build_go() {
    pkg_get golang-go git ca-certificates >/dev/null || die "Could not install Go"
    cd "$(root_of "$1" go.mod)" || die "go.mod not found"
    export GOTOOLCHAIN=auto GOFLAGS="-buildvcs=false" CGO_ENABLED="${CGO_ENABLED:-1}"
    stage 30 "go build"
    go version 2>&1
    mkdir -p bsout
    go build -p "$JOBS" -o bsout/ ./... 2>&1 || go build -o bsout/ . 2>&1 || return 1
    local f; for f in bsout/*; do emit "$f"; done
}

# ---------------------------------------------------------------- Node.js / web (npm, yarn, pnpm)
ensure_node() {
    export PATH="/opt/bsource/node/bin:$PATH"
    [ -x /opt/bsource/node/bin/node ] && return 0
    stage 10 "Node.js"
    pkg_get curl xz-utils python3 build-essential >/dev/null
    local v; v=$(curl -fsSL https://nodejs.org/dist/index.json | python3 -c 'import json,sys; print(next(r["version"] for r in json.load(sys.stdin) if r["lts"]))' 2>/dev/null)
    [ -n "$v" ] || v=v22.12.0
    rm -rf /opt/bsource/node /opt/bsource/_node
    fetch_extract "https://nodejs.org/dist/$v/node-$v-linux-arm64.tar.xz" /opt/bsource/_node \
        && mv /opt/bsource/_node/node-* /opt/bsource/node && rm -rf /opt/bsource/_node
    [ -x /opt/bsource/node/bin/node ] || pkg_get nodejs npm
}
node_install() {
    if [ -f pnpm-lock.yaml ]; then corepack enable 2>/dev/null; pnpm install 2>&1
    elif [ -f yarn.lock ]; then corepack enable 2>/dev/null; yarn install 2>&1
    elif [ -f package-lock.json ]; then npm ci 2>&1 || npm install 2>&1
    else npm install 2>&1
    fi
}
node_run() { if [ -f pnpm-lock.yaml ]; then pnpm run "$@"; elif [ -f yarn.lock ]; then yarn run "$@"; else npm run "$@"; fi; }
build_node() {
    ensure_node || die "Could not install Node.js"
    cd "$(root_of "$1" package.json)" || die "package.json not found"
    node -v
    stage 30 "Installing packages"
    node_install || return 1
    if grep -q '"build"[[:space:]]*:' package.json; then
        stage 60 "npm run build"
        node_run build 2>&1 || return 1
        local d; for d in dist build out public/build .output; do [ -d "$d" ] && find "$d" -newer "$marker" -type f | grep -q . && { emit "$d"; break; }; done
    fi
    [ "$BS_N" -gt 0 ] || { stage 80 "npm pack"; npm pack 2>&1 | tail -n 3; emit_new . '.*\.tgz$'; }
}

# ---------------------------------------------------------------- React Native → Android project after npm install
prepare_reactnative() {
    ensure_node || die "Could not install Node.js"
    cd "$(root_of "$1" package.json)" || die "package.json not found"
    stage 10 "Installing JavaScript packages (React Native)"
    node_install || die "npm install failed — see the log"
}

# ---------------------------------------------------------------- Python
build_python() {
    pkg_get python3 python3-pip python3-venv python3-dev build-essential >/dev/null || die "Could not install Python"
    local r; r=$(root_of "$1" pyproject.toml setup.py setup.cfg)
    [ -n "$r" ] || r=$(root_of "$1" requirements.txt main.py app.py __main__.py)
    cd "${r:-$1}" || return 1
    local venv=/root/.bsource/pyenv
    [ -x "$venv/bin/python" ] || python3 -m venv "$venv" 2>&1
    . "$venv/bin/activate"
    pip install -q --upgrade pip build wheel 2>&1 | tail -n 3
    if [ -f pyproject.toml ] || [ -f setup.py ]; then
        stage 40 "python -m build"
        python -m build 2>&1 || return 1
        emit_new dist '.*\.(whl|tar\.gz)$'
    else
        stage 40 "PyInstaller (single executable)"
        [ -f requirements.txt ] && pip install -r requirements.txt 2>&1 | tail -n 5
        pip install -q pyinstaller 2>&1 | tail -n 3
        local m; for m in main.py app.py __main__.py run.py; do [ -f "$m" ] && break; done
        [ -f "$m" ] || m=$(grep -l '__name__ *== *.__main__.' ./*.py 2>/dev/null | head -1)
        [ -n "$m" ] && [ -f "$m" ] || die "No main.py / app.py / pyproject.toml found"
        pyinstaller --onefile --noconfirm "$m" 2>&1 || return 1
        emit_new dist '.*'
    fi
}

# ---------------------------------------------------------------- Java (Maven, Gradle without Android, plain .java)
java_on() {
    { [ -z "$BS_JAVA" ] || [ "$BS_JAVA" = auto ]; } && BS_JAVA=17
    pkg_get "openjdk-$BS_JAVA-jdk-headless" >/dev/null || die "Could not install Java $BS_JAVA"
    export JAVA_HOME="$(java_home "$BS_JAVA")" PATH="$(java_home "$BS_JAVA")/bin:$PATH"
}
build_maven() {
    java_on
    cd "$(root_of "$1" pom.xml)" || die "pom.xml not found"
    stage 30 "mvn package"
    if [ -f mvnw ]; then chmod +x mvnw; M="./mvnw"; else pkg_get maven >/dev/null || die "Could not install Maven"; M=mvn; fi
    $M -B -DskipTests package 2>&1 || return 1
    emit_new . '.*/target/[^/]*\.(jar|war|ear|zip)$'
}
build_gradlejava() {
    java_on
    cd "$(root_of "$1" settings.gradle settings.gradle.kts build.gradle build.gradle.kts)" || die "Gradle project not found"
    local G
    if [ -f gradlew ]; then chmod +x gradlew; G="bash ./gradlew"
    else local gv; gv=$(wrapper_version); G=$(ensure_gradle_dist "${gv:-8.14.3}") || die "Could not download Gradle"; G="bash $G"; fi
    stage 30 "gradle assemble"
    $G assemble --no-daemon --console=plain -x test -Dorg.gradle.jvmargs=-Xmx${BS_MEM}m $BS_ARGS 2>&1 || return 1
    emit_new . '.*/build/(libs|distributions)/[^/]*\.(jar|war|zip|tar)$'
}
build_java() {
    java_on
    stage 30 "javac"
    mkdir -p "$TMPDIR/bs_classes_$$"
    find . -name '*.java' -not -path '*/test/*' > "$TMPDIR/bs_src_$$"
    javac -encoding UTF-8 -d "$TMPDIR/bs_classes_$$" @"$TMPDIR/bs_src_$$" 2>&1 || return 1
    local main; main=$(grep -l 'static[[:space:]]\+void[[:space:]]\+main' $(cat "$TMPDIR/bs_src_$$") 2>/dev/null | head -1)
    local cls=""
    if [ -n "$main" ]; then
        local pk; pk=$(grep -oE '^[[:space:]]*package[[:space:]]+[A-Za-z0-9_.]+' "$main" | awk '{print $2}')
        cls="$(basename "$main" .java)"; [ -n "$pk" ] && cls="$pk.$cls"
    fi
    mkdir -p bsout
    if [ -n "$cls" ]; then jar cfe "bsout/$BS_NAME.jar" "$cls" -C "$TMPDIR/bs_classes_$$" . 2>&1
    else jar cf "bsout/$BS_NAME.jar" -C "$TMPDIR/bs_classes_$$" . 2>&1; fi
    rm -rf "$TMPDIR/bs_classes_$$" "$TMPDIR/bs_src_$$"
    emit "bsout/$BS_NAME.jar"
}

# ---------------------------------------------------------------- .NET (C#, F#, VB)
ensure_dotnet() {   # ensure_dotnet channel
    export DOTNET_ROOT=/opt/bsource/dotnet PATH="/opt/bsource/dotnet:$PATH" DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1
    dotnet --list-sdks 2>/dev/null | grep -q "^$1" && return 0
    stage 10 ".NET SDK $1"
    pkg_get curl ca-certificates libicu74 libssl3 zlib1g >/dev/null
    curl -fsSL https://dot.net/v1/dotnet-install.sh -o "$TMPDIR/dotnet-install.sh" || return 1
    bash "$TMPDIR/dotnet-install.sh" --channel "$1" --install-dir /opt/bsource/dotnet 2>&1 | tail -n 5
    dotnet --version >/dev/null 2>&1
}
build_dotnet() {
    local p; p=$(first_file "$1" '*.sln' '*.slnx' '*.csproj' '*.fsproj' '*.vbproj')
    [ -n "$p" ] || die "No .sln / .csproj found"
    cd "$(dirname "$p")" || return 1
    local ch; ch=$(grep -rhoE '<TargetFrameworks?>net[0-9]+\.[0-9]' --include='*.*proj' --include='Directory.Build.props' . 2>/dev/null | grep -oE '[0-9]+\.[0-9]' | sort -V | tail -1)
    ensure_dotnet "${ch:-8.0}" || die "Could not install the .NET SDK"
    stage 30 "dotnet publish"
    dotnet publish "$(basename "$p")" -c Release -o bsout 2>&1 || dotnet build "$(basename "$p")" -c Release -o bsout 2>&1 || return 1
    emit bsout
}

# ---------------------------------------------------------------- Flutter (experimental on ARM)
build_flutter() {
    pkg_get git curl unzip xz-utils zip clang cmake ninja-build pkg-config >/dev/null
    local F=/root/.bsource/flutter
    if [ ! -x "$F/bin/flutter" ]; then
        stage 10 "Flutter SDK (first time)"
        git clone --depth 1 -b stable https://github.com/flutter/flutter.git "$F" 2>&1 | tail -n 2
    fi
    export PATH="$F/bin:$PATH" FLUTTER_SUPPRESS_ANALYTICS=true
    cd "$(root_of "$1" pubspec.yaml)" || die "pubspec.yaml not found"
    android_sdk_ready
    flutter config --no-analytics --android-sdk "$SDK" >/dev/null 2>&1
    stage 30 "flutter pub get"
    flutter pub get 2>&1 || return 1
    stage 50 "flutter build apk"
    flutter build apk --release 2>&1 || { echo "› release build failed on ARM → trying a debug APK"; flutter build apk --debug 2>&1; } || return 1
    emit_new build '.*\.apk$'
}
android_sdk_ready() {   # Java + aapt2 + SDK basics for Flutter
    java_on
    ensure_aapt2 >/dev/null 2>&1
    ensure_cmdline >/dev/null 2>&1 && licenses >/dev/null 2>&1
    ensure_platform 35 >/dev/null 2>&1; ensure_buildtools 35.0.0 >/dev/null 2>&1; fix_buildtools >/dev/null 2>&1
}

# ---------------------------------------------------------------- any folder with a build script (.sh / build.py / just / task …)
find_build_script() {   # shallowest build script; prints its path
    local f
    f=$(first_file "$1" build.sh build.bash build-linux.sh build_linux.sh make.sh compile.sh build_all.sh build-all.sh setup.sh)
    [ -n "$f" ] || f=$(find "$1" -maxdepth 2 -type f -iname '*build*.sh' -not -path '*/.git/*' 2>/dev/null | head -1)
    [ -n "$f" ] || f=$(first_file "$1" build.py make.py compile.py)
    [ -n "$f" ] || f=$(first_file "$1" justfile Justfile Taskfile.yml Taskfile.yaml xmake.lua build.zig build.ninja build.xml premake5.lua)
    [ -n "$f" ] || { local n; n=$(find "$1" -maxdepth 1 -type f -name '*.sh' 2>/dev/null | wc -l); [ "$n" = 1 ] && f=$(find "$1" -maxdepth 1 -type f -name '*.sh'); }
    [ -n "$f" ] && echo "$f"
}
crlf_fix() {   # Windows line endings break scripts and makefiles
    find "$1" -maxdepth 4 -type f \( -name '*.sh' -o -name '*.bash' -o -name 'Makefile*' -o -name '*.mk' -o -name 'configure' -o -name '*.py' \) \
        -not -path '*/.git/*' -not -path '*/node_modules/*' 2>/dev/null | while read -r f; do
        grep -q $'\r' "$f" 2>/dev/null && sed -i 's/\r$//' "$f" && echo "› fixed Windows line endings: ${f#$1/}"
    done
}
build_script() {   # returns 2 when there is no build script
    local s; s=$(find_build_script "$1")
    [ -n "$s" ] || return 2
    cd "$(dirname "$s")" || return 1
    pkg_get build-essential clang make cmake pkg-config python3 >/dev/null
    local b; b=$(basename "$s")
    stage 30 "Running $b"
    case "$b" in
        *.py) python3 "./$b" 2>&1 ;;
        justfile|Justfile) pkg_get just >/dev/null; just 2>&1 ;;
        Taskfile.*) pkg_get go-task >/dev/null 2>&1; (command -v go-task >/dev/null && go-task || task) 2>&1 ;;
        xmake.lua) pkg_get xmake >/dev/null; xmake -y 2>&1 ;;
        build.zig) ensure_zig && zig build -Doptimize=ReleaseFast 2>&1 ;;
        build.ninja) pkg_get ninja-build >/dev/null; ninja -j "$JOBS" 2>&1 ;;
        build.xml) java_on; pkg_get ant >/dev/null; ant 2>&1 ;;
        premake5.lua) echo "! premake5 has no ARM package — add a Makefile or CMakeLists.txt"; return 1 ;;
        *) chmod +x "$b"; bash "./$b" 2>&1 ;;
    esac
}

# ---------------------------------------------------------------- more languages
ensure_zig() {
    export PATH="/opt/bsource/zig:$PATH"
    command -v zig >/dev/null 2>&1 && return 0
    stage 10 "Zig"
    local u; u=$(curl -fsSL https://ziglang.org/download/index.json | python3 -c 'import json,sys; print(json.load(sys.stdin)["0.14.1"]["aarch64-linux"]["tarball"])' 2>/dev/null)
    [ -n "$u" ] || u="https://ziglang.org/download/0.14.1/zig-aarch64-linux-0.14.1.tar.xz"
    rm -rf /opt/bsource/zig /opt/bsource/_zig
    fetch_extract "$u" /opt/bsource/_zig && mv /opt/bsource/_zig/zig-* /opt/bsource/zig; rm -rf /opt/bsource/_zig
    command -v zig >/dev/null 2>&1
}
build_zig() { cd "$(root_of "$1" build.zig)" || return 1; ensure_zig || die "Could not install Zig"; stage 30 "zig build"; zig build -Doptimize=ReleaseFast 2>&1 || return 1; emit_new zig-out '.*'; }
build_nim() {
    pkg_get nim build-essential >/dev/null || die "Could not install Nim"
    local n; n=$(first_file "$1" '*.nimble'); cd "$(dirname "${n:-$(first_file "$1" '*.nim')}")" || return 1
    stage 30 "nim"
    if [ -n "$n" ]; then nimble build -y 2>&1 || return 1; else nim c -d:release -o:"bsout/$BS_NAME" "$(ls ./*.nim | head -1)" 2>&1 || return 1; fi
    local f; for f in bsout/* ./*; do [ -f "$f" ] && [ -x "$f" ] && head -c 4 "$f" | grep -q ELF && emit "$f"; done; true
}
build_dart() {
    pkg_get curl unzip >/dev/null
    export PATH="/opt/bsource/dart-sdk/bin:$PATH"
    if ! command -v dart >/dev/null 2>&1; then
        stage 10 "Dart SDK"
        fetch_extract "https://storage.googleapis.com/dart-archive/channels/stable/release/latest/sdk/dartsdk-linux-arm64-release.zip" /opt/bsource || die "Could not install Dart"
    fi
    cd "$(root_of "$1" pubspec.yaml)" || return 1
    stage 30 "dart compile"
    dart pub get 2>&1 || return 1
    mkdir -p bsout
    local m; m=$(ls bin/*.dart 2>/dev/null | head -1); [ -n "$m" ] || m=$(ls lib/main.dart 2>/dev/null)
    [ -n "$m" ] || die "No bin/*.dart found"
    dart compile exe "$m" -o "bsout/$BS_NAME" 2>&1 || return 1
    emit "bsout/$BS_NAME"
}
build_haskell() {
    pkg_get ghc cabal-install build-essential zlib1g-dev >/dev/null || die "Could not install GHC"
    cd "$(root_of "$1" '*.cabal' stack.yaml)" || return 1
    stage 30 "cabal build"
    cabal update 2>&1 | tail -n 2; cabal build -j"$JOBS" 2>&1 || return 1
    mkdir -p bsout; cabal install --installdir=bsout --install-method=copy --overwrite-policy=always 2>&1 | tail -n 3
    local f; for f in bsout/*; do emit "$f"; done
}
build_ant() { java_on; pkg_get ant >/dev/null || die "Could not install Ant"; cd "$(root_of "$1" build.xml)" || return 1; stage 30 "ant"; ant 2>&1 || return 1; emit_new . '.*\.(jar|war|apk|zip)$'; }

# ---------------------------------------------------------------- install what a failed build was missing, then try again
fix_missing() {   # fix_missing logfile target → 0 when something new was installed
    local log="$1" tgt="$2" got=1 q p
    pkg_get apt-file >/dev/null
    [ -f /root/.bsource/.aptfile ] || { echo "› indexing Ubuntu packages (first time)"; apt-file update >/dev/null 2>&1 && touch /root/.bsource/.aptfile; }
    afind() { apt-file search -x "$1" 2>/dev/null | grep -vE '^(libc6|gcc-[0-9]+-cross|lib.*-cross)' | head -1 | cut -d: -f1; }
    try() { [ -n "$1" ] || return; pkg_has "$1" && return; echo "› installing missing $2 → $1"; pkg_get "$1" >/dev/null && got=0; }
    # commands
    for q in $(grep -ohE "(: |sh: [0-9]+: )[A-Za-z0-9_.+-]+: (command not found|not found)" "$log" | sed -E 's/.*: ([A-Za-z0-9_.+-]+): (command )?not found/\1/' | sort -u) \
             $(grep -ohE "make(\[[0-9]+\])?: [A-Za-z0-9_.+-]+: No such file or directory" "$log" | awk '{print $2}' | tr -d : | sort -u); do
        case "$q" in python) q=python3 ;; esac
        try "$(afind "/(usr/)?s?bin/$q\$")" "command $q"
    done
    if [ "$tgt" = native ]; then
        for q in $(grep -ohE "fatal error: '?[^': ]+\.(h|hpp|hh|hxx)'?" "$log" | sed -E "s/fatal error: '?//; s/'\$//" | sort -u); do
            try "$(afind "/usr/include/(.*/)?${q//./\\.}\$")" "header $q"
        done
        for q in $(grep -ohE "cannot find -l[A-Za-z0-9_+.-]+" "$log" | sed 's/cannot find -l//' | sort -u); do
            try "$(afind "/lib/(aarch64-linux-gnu/)?lib${q//./\\.}\.(so|a)\$")" "library $q"
        done
        for q in $(grep -ohE "(No package '|Package )[A-Za-z0-9_+.-]+(' found| was not found)" "$log" | sed -E "s/No package '|Package //; s/' found| was not found//" | sort -u); do
            p=$(cmake_pkg "$q"); [ -n "$p" ] && { for x in $p; do try "$x" "pkg-config $q"; done; continue; }
            try "$(afind "/pkgconfig/${q//./\\.}\.pc\$")" "pkg-config $q"
        done
        for q in $(grep -ohE 'provided by "[A-Za-z0-9_+-]+"|Could NOT find [A-Za-z0-9_+-]+' "$log" | sed -E 's/provided by "|Could NOT find //; s/"//' | sort -u); do
            p=$(cmake_pkg "$q")
            [ -n "$p" ] && { for x in $p; do try "$x" "CMake package $q"; done; continue; }
            p=$(afind "/(${q}Config|$(echo "$q" | tr 'A-Z' 'a-z')-config)\.cmake\$")
            [ -n "$p" ] || p=$(afind "/Find${q}\.cmake\$")
            try "$p" "CMake package $q"
        done
    fi
    return $got
}
cmake_pkg() {   # common CMake / pkg-config names → Ubuntu packages
    case "$(echo "$1" | tr 'A-Z' 'a-z')" in
        zlib) echo zlib1g-dev ;; png) echo libpng-dev ;; jpeg) echo libjpeg-dev ;; tiff) echo libtiff-dev ;; gif) echo libgif-dev ;;
        opengl|gl) echo "libgl-dev libglu1-mesa-dev libegl-dev" ;; glew) echo libglew-dev ;; glfw|glfw3) echo libglfw3-dev ;; glm) echo libglm-dev ;;
        vulkan) echo "libvulkan-dev glslang-tools" ;; x11) echo "libx11-dev libxext-dev libxrandr-dev libxinerama-dev libxcursor-dev libxi-dev" ;;
        curl) echo libcurl4-openssl-dev ;; openssl) echo libssl-dev ;; freetype) echo libfreetype-dev ;; harfbuzz) echo libharfbuzz-dev ;;
        sdl2) echo libsdl2-dev ;; sdl2_image) echo libsdl2-image-dev ;; sdl2_ttf) echo libsdl2-ttf-dev ;; sdl2_mixer) echo libsdl2-mixer-dev ;;
        sdl2_net) echo libsdl2-net-dev ;; sdl3) echo libsdl3-dev ;; sfml) echo libsfml-dev ;; openal) echo libopenal-dev ;;
        boost) echo libboost-all-dev ;; liblzma|lzma) echo liblzma-dev ;; bzip2) echo libbz2-dev ;; zstd) echo libzstd-dev ;; lz4) echo liblz4-dev ;;
        sqlite3) echo libsqlite3-dev ;; curses|ncurses) echo libncurses-dev ;; expat) echo libexpat1-dev ;; libxml2) echo libxml2-dev ;;
        fmt) echo libfmt-dev ;; spdlog) echo libspdlog-dev ;; nlohmann_json) echo nlohmann-json3-dev ;; eigen3) echo libeigen3-dev ;;
        yaml-cpp) echo libyaml-cpp-dev ;; protobuf) echo "libprotobuf-dev protobuf-compiler" ;; gtest) echo libgtest-dev ;;
        lua) echo liblua5.4-dev ;; ffmpeg) echo "libavcodec-dev libavformat-dev libavutil-dev libswscale-dev libswresample-dev" ;;
        opus) echo libopus-dev ;; ogg) echo libogg-dev ;; vorbis) echo libvorbis-dev ;; flac) echo libflac-dev ;; sndfile) echo libsndfile1-dev ;;
        alsa) echo libasound2-dev ;; pulseaudio) echo libpulse-dev ;; icu) echo libicu-dev ;; python3|python) echo python3-dev ;;
        qt5|qt5widgets|qt5core) echo qtbase5-dev ;; qt6|qt6core|qt6widgets) echo qt6-base-dev ;; gtk3) echo libgtk-3-dev ;;
        fontconfig) echo libfontconfig-dev ;; libusb|usb) echo libusb-1.0-0-dev ;; threads|pkgconfig) echo pkg-config ;;
        wayland) echo libwayland-dev ;; drm|libdrm) echo libdrm-dev ;; gbm) echo libgbm-dev ;; mbedtls) echo libmbedtls-dev ;;
        libuv) echo libuv1-dev ;; libevent) echo libevent-dev ;; capstone) echo libcapstone-dev ;; libzip) echo libzip-dev ;;
        minizip) echo libminizip-dev ;; libarchive) echo libarchive-dev ;; jsoncpp) echo libjsoncpp-dev ;; tinyxml2) echo libtinyxml2-dev ;;
        assimp) echo libassimp-dev ;; bullet) echo libbullet-dev ;; box2d) echo libbox2d-dev ;; raylib) echo "" ;;
    esac
}
run_with_fixes() {   # run_with_fixes target build-function [args] → builds, installs what was missing, retries (3 tries)
    local tgt="$1"; shift
    local i rc log="$TMPDIR/bs_out_$$"
    for i in 1 2 3; do
        ( "$@" ) 2>&1 | tee "$log"; rc=${PIPESTATUS[0]}
        [ "$rc" = 0 ] && break
        [ "$i" = 3 ] && break
        fix_missing "$log" "$tgt" || break
        echo "› trying again with the missing parts installed ($((i+1))/3)"
    done
    rm -f "$log"
    return $rc
}

# ---------------------------------------------------------------- M7L project libraries (kept in /root/.bsource/libs)
LIBS_DIR=/root/.bsource/libs
# id | git url (or tarball) | extra clone options
m7l_lib_src() {
    case "$1" in
        dosbox)      echo "tar https://sourceforge.net/projects/dosbox/files/dosbox/0.74-3/dosbox-0.74-3.tar.gz/download" ;;
        pcsx_rearmed) echo "git https://github.com/libretro/pcsx_rearmed --recursive --shallow-submodules" ;;
        pcsx2)       echo "git https://github.com/PCSX2/pcsx2" ;;
        daedalusx64) echo "git https://github.com/masterfeizz/DaedalusX64-libretro" ;;
        dr_libs)     echo "git https://github.com/mackron/dr_libs" ;;
        stb)         echo "git https://github.com/nothings/stb" ;;
        imgui)       echo "git https://github.com/ocornut/imgui" ;;
        glslang)     echo "git https://github.com/KhronosGroup/glslang" ;;
        shaderc)     echo "git https://github.com/google/shaderc" ;;
        rapidyaml)   echo "git https://github.com/biojppm/rapidyaml -b v0.12.1 --recursive --shallow-submodules" ;;
        sdl2)        echo "git https://github.com/libsdl-org/SDL -b SDL2" ;;
        miniz)       echo "git https://github.com/richgel999/miniz" ;;
        libchdr)     echo "git https://github.com/rtissera/libchdr --recursive --shallow-submodules" ;;
        zstd)        echo "git https://github.com/facebook/zstd" ;;
        mesa)        echo "git https://gitlab.freedesktop.org/mesa/mesa" ;;
        openorbis)   echo "git https://github.com/OpenOrbis/OpenOrbis-PS4-Toolchain" ;;
        psl1ght)     echo "git https://github.com/ps3dev/PSL1GHT" ;;
        ps2sdk)      echo "git https://github.com/ps2dev/ps2sdk" ;;
        termux)      echo "git https://github.com/termux/termux-app" ;;
        *) return 1 ;;
    esac
}
M7L_ALL="dosbox pcsx_rearmed pcsx2 daedalusx64 dr_libs stb imgui glslang shaderc rapidyaml sdl2 miniz libchdr zstd mesa openorbis psl1ght ps2sdk termux"

m7l_get() {   # m7l_get id
    case "$1" in
        python) pkg_get python3 python3-pip python3-venv >/dev/null || return 1
                local venv=/root/.bsource/pyenv
                [ -x "$venv/bin/python" ] || python3 -m venv "$venv" 2>&1
                "$venv/bin/pip" install -q Pillow playwright 2>&1 | tail -n 5
                "$venv/bin/playwright" install --with-deps chromium 2>&1 | tail -n 8
                "$venv/bin/python" -c 'import PIL, playwright' ; return $? ;;
        fonts)  pkg_get fonts-dejavu-core fontconfig curl >/dev/null || return 1
                local fd=/usr/share/fonts/truetype/m7l g="https://github.com/google/fonts/raw/main/ofl" f
                mkdir -p "$fd"
                for f in "montserrat/Montserrat%5Bwght%5D.ttf" "cairo/Cairo%5Bslnt%2Cwght%5D.ttf" \
                         "almarai/Almarai-Regular.ttf" "almarai/Almarai-Bold.ttf"; do
                    curl -fsSL -o "$fd/$(basename "$f" | sed 's/%5B/[/; s/%5D/]/; s/%2C/,/')" "$g/$f" || echo "! $f"
                done
                fc-cache -f >/dev/null 2>&1; ls "$fd"; return 0 ;;
    esac
    local spec; spec=$(m7l_lib_src "$1") || { echo "! unknown library $1"; return 1; }
    local d="$LIBS_DIR/$1" kind url
    [ -d "$d" ] && [ -n "$(ls -A "$d" 2>/dev/null)" ] && { echo "› $1 already here"; return 0; }
    mkdir -p "$LIBS_DIR"; rm -rf "$d" 2>/dev/null
    read -r kind url rest <<< "$spec"
    if [ "$kind" = tar ]; then
        fetch_extract "$url" "$d.tmp" || return 1
        local top; top=$(find "$d.tmp" -mindepth 1 -maxdepth 1 -type d | head -1)
        mv "${top:-$d.tmp}" "$d"; rm -rf "$d.tmp"
    else
        pkg_get git ca-certificates >/dev/null
        echo "› git clone $url"
        # shellcheck disable=SC2086
        git clone --depth 1 $rest "$url" "$d" 2>&1 | grep -v '^Updating files' | tail -n 3
        [ -d "$d/.git" ] || return 1
    fi
    du -sh "$d" 2>/dev/null | cut -f1 | sed "s/^/› $1: /"
}

# use what the project needs: scan its sources for well-known headers and fetch the matching library
m7l_auto() {   # m7l_auto projectdir
    local d="$1" need=() inc=""
    hit() { grep -rqsE --include='*.c' --include='*.cc' --include='*.cpp' --include='*.cxx' --include='*.h' --include='*.hpp' \
                --include='CMakeLists.txt' --include='Makefile' --include='*.mk' --exclude-dir=.git "$1" "$d" 2>/dev/null; }
    hit '#include *[<"](dr_mp3|dr_flac|dr_wav)\.h'   && ! find "$d" -name 'dr_mp3.h' -o -name 'dr_flac.h' -o -name 'dr_wav.h' | grep -q . && need+=(dr_libs)
    hit '#include *[<"]stb_[a-z_]+\.(h|c)'             && ! find "$d" -name 'stb_*.h' -o -name 'stb_vorbis.c' | grep -q . && need+=(stb)
    hit '#include *[<"]imgui\.h'                        && ! find "$d" -name imgui.h | grep -q . && need+=(imgui)
    hit '#include *[<"]miniz\.h'                        && ! find "$d" -name miniz.h | grep -q . && need+=(miniz)
    hit '#include *[<"]libchdr/'                        && ! find "$d" -path '*libchdr*' -name chd.h | grep -q . && need+=(libchdr)
    hit '#include *[<"]ryml'                            && ! find "$d" -name 'ryml*.hpp' | grep -q . && need+=(rapidyaml)
    # system packages for native Linux builds
    if [ "${TARGET:-native}" = native ]; then
        hit '#include *[<"]SDL2?/|#include *[<"]SDL\.h|find_package\(SDL2' && pkg_get libsdl2-dev >/dev/null
        hit '#include *[<"]zstd\.h|find_package\(zstd'  && pkg_get libzstd-dev >/dev/null
        hit 'glslang/|find_package\(glslang'            && pkg_get glslang-dev glslang-tools >/dev/null
        hit 'shaderc/|find_package\(shaderc'            && pkg_get libshaderc-dev >/dev/null
        hit '#include *[<"]vulkan/'                     && pkg_get libvulkan-dev mesa-vulkan-drivers >/dev/null
    fi
    local l
    for l in "${need[@]}"; do stage 9 "Library: $l"; m7l_get "$l"; done
    # header search paths for every library we keep
    for l in dr_libs stb imgui miniz libchdr/include rapidyaml/src; do
        [ -d "$LIBS_DIR/$l" ] && inc="$inc:$LIBS_DIR/$l"
    done
    [ -d "$LIBS_DIR/imgui/backends" ] && inc="$inc:$LIBS_DIR/imgui/backends"
    if [ -n "$inc" ]; then
        export CPATH="${inc#:}${CPATH:+:$CPATH}"
        export M7L_LIBS="$LIBS_DIR"
        echo "› libraries on the include path: ${inc#:}"
    fi
}
