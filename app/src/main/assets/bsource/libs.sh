# Building source — install / remove build components inside Ubuntu
# usage: libs.sh install|remove <component>...
. "$(dirname "$0")/common.sh"
. "$(dirname "$0")/ps.sh"
. "$(dirname "$0")/langs.sh"
echo $$ > "$BS_PIDFILE"

action="$1"; shift
total=$#; i=0; fail=0
for c in "$@"; do
    i=$((i+1))
    stage $(( (i-1) * 100 / total )) "$c"
    case "$action:$c" in
        install:java17)     pkg_get openjdk-17-jdk-headless ;;
        install:java21)     pkg_get openjdk-21-jdk-headless ;;
        install:java8)      pkg_get openjdk-8-jdk-headless ;;
        install:java11)     pkg_get openjdk-11-jdk-headless ;;
        install:platform-old) pkg_get openjdk-17-jdk-headless && for v in 21 22 23 24 25 26 27; do ensure_platform $v; done ;;
        install:buildtools-old) pkg_get openjdk-17-jdk-headless && ensure_aapt2 && for v in 30.0.3 33.0.2 34.0.0; do ensure_buildtools $v; done ;;
        install:gradle-old) for v in 4.10.3 5.6.4 6.9.4 7.6.4; do ensure_gradle_dist $v >/dev/null || echo "! Gradle $v"; done ;;
        install:gradle)     ensure_gradle_dist 8.14.3 >/dev/null ;;
        install:aapt2)      ensure_aapt2 && fix_buildtools ;;
        install:git)        pkg_get git ;;
        install:cmdline)    pkg_get openjdk-17-jdk-headless && ensure_cmdline && licenses ;;
        install:platform-*) pkg_get openjdk-17-jdk-headless && ensure_platform "${c#platform-}" ;;
        install:buildtools) pkg_get openjdk-17-jdk-headless && ensure_aapt2 && ensure_buildtools 35.0.0 ;;
        install:ndk)        ensure_ndk ;;
        install:ps5)        ensure_ps5 ;;
        install:ps4)        ensure_ps4 ;;
        install:ps3)        ensure_ps3 ;;
        install:ps2)        ensure_ps2 ;;
        install:ps1)        ensure_ps1 ;;
        install:rust)       ensure_rust ;;
        install:go)         pkg_get golang-go ;;
        install:node)       ensure_node ;;
        install:python)     pkg_get python3 python3-pip python3-venv python3-dev build-essential ;;
        install:maven)      pkg_get openjdk-17-jdk-headless maven ;;
        install:dotnet)     ensure_dotnet 8.0 ;;
        install:flutter)    pkg_get git && { [ -x /root/.bsource/flutter/bin/flutter ] || git clone --depth 1 -b stable https://github.com/flutter/flutter.git /root/.bsource/flutter 2>&1 | tail -n 2; } ;;
        install:cc)         pkg_get build-essential clang make cmake ninja-build pkg-config autoconf automake libtool meson ;;
        install:m7l-all)    f=0; for l in $M7L_ALL python fonts; do echo "━━ $l"; m7l_get "$l" || { echo "✗ $l"; f=1; }; done; [ $f = 0 ] ;;
        install:m7l-*)      m7l_get "${c#m7l-}" ;;
        install:zig)        ensure_zig ;;
        install:nim)        pkg_get nim ;;
        install:dart)       export PATH="/opt/bsource/dart-sdk/bin:$PATH"; command -v dart >/dev/null || fetch_extract "https://storage.googleapis.com/dart-archive/channels/stable/release/latest/sdk/dartsdk-linux-arm64-release.zip" /opt/bsource ;;
        install:haskell)    pkg_get ghc cabal-install ;;
        install:buildsys)   pkg_get build-essential clang lld make cmake ninja-build meson autoconf automake libtool pkg-config scons ant just xmake nasm yasm bison flex gettext xxd patch python3 ;;
        install:devlibs)    pkg_get libsdl2-dev libsdl2-image-dev libsdl2-ttf-dev libsdl2-mixer-dev libgl-dev libglu1-mesa-dev libegl-dev libglew-dev libglfw3-dev \
                              libvulkan-dev libx11-dev libcurl4-openssl-dev libssl-dev zlib1g-dev libpng-dev libjpeg-dev libfreetype-dev libzstd-dev liblzma-dev \
                              libbz2-dev libsqlite3-dev libncurses-dev libopenal-dev libogg-dev libvorbis-dev libflac-dev libopus-dev libasound2-dev libpulse-dev \
                              libfmt-dev nlohmann-json3-dev libglm-dev libeigen3-dev libyaml-cpp-dev libusb-1.0-0-dev libxml2-dev libarchive-dev ;;
        install:aptfile)    pkg_get apt-file && apt-file update 2>&1 | tail -n 2 && touch /root/.bsource/.aptfile ;;
        install:required)   pkg_get openjdk-17-jdk-headless unzip zip curl && ensure_aapt2 && ensure_cmdline && licenses \
                              && ensure_platform 34 && ensure_platform 35 && ensure_buildtools 35.0.0 ;;
        remove:java17)      pkg_del openjdk-17-jdk-headless openjdk-17-jre-headless ;;
        remove:java21)      pkg_del openjdk-21-jdk-headless openjdk-21-jre-headless ;;
        remove:java8)       pkg_del openjdk-8-jdk-headless openjdk-8-jre-headless ;;
        remove:java11)      pkg_del openjdk-11-jdk-headless openjdk-11-jre-headless ;;
        remove:platform-old) for v in 21 22 23 24 25 26 27; do rm -rf "$SDK/platforms/android-$v"; done ;;
        remove:buildtools-old) for v in 30.0.3 33.0.2 34.0.0; do rm -rf "$SDK/build-tools/$v"; done ;;
        remove:gradle-old)  for v in 4.10.3 5.6.4 6.9.4 7.6.4; do rm -rf /root/.bsource/gradle/gradle-$v; done ;;
        remove:gradle)      rm -rf /root/.bsource/gradle ;;
        remove:aapt2)       rm -f "$BIN"/aapt "$BIN"/aapt2 "$BIN"/zipalign "$BIN"/aidl "$BIN"/dexdump "$BIN"/split-select ;;
        remove:git)         pkg_del git ;;
        remove:cmdline)     rm -rf "$SDK/cmdline-tools" ;;
        remove:platform-*)  rm -rf "$SDK/platforms/android-${c#platform-}" ;;
        remove:buildtools)  rm -rf "$SDK/build-tools" ;;
        remove:ndk)         rm -rf "$SDK/ndk" ;;
        remove:ps5)         rm -rf "$TC/ps5-payload-sdk" ;;
        remove:ps4)         rm -rf "$TC/OpenOrbis" "$SRC/create-fself" "$SRC/create-gp4" ;;
        remove:ps3)         rm -rf "$TC/ps3dev" "$SRC/ps3toolchain" ;;
        remove:ps2)         rm -rf "$TC/ps2dev" "$SRC/ps2dev" ;;
        remove:ps1)         rm -rf "$TC/mipsel-none-elf" "$TC/psn00bsdk" "$SRC/ps1" "$SRC/PSn00bSDK" ;;
        remove:rust)        rm -rf /root/.cargo /root/.rustup; pkg_del cargo rustc ;;
        remove:go)          pkg_del golang-go; rm -rf /root/go ;;
        remove:node)        rm -rf /opt/bsource/node /root/.npm; pkg_del nodejs npm ;;
        remove:python)      rm -rf /root/.bsource/pyenv /root/.cache/pip ;;
        remove:maven)       pkg_del maven; rm -rf /root/.m2 ;;
        remove:dotnet)      rm -rf /opt/bsource/dotnet /root/.nuget /root/.dotnet ;;
        remove:flutter)     rm -rf /root/.bsource/flutter /root/.pub-cache ;;
        remove:cc)          pkg_del clang cmake ninja-build meson ;;
        remove:m7l-all)     rm -rf "$LIBS_DIR" /usr/share/fonts/truetype/m7l /root/.cache/ms-playwright; true ;;
        remove:m7l-python)  /root/.bsource/pyenv/bin/pip uninstall -y -q playwright Pillow 2>/dev/null; rm -rf /root/.cache/ms-playwright; true ;;
        remove:m7l-fonts)   rm -rf /usr/share/fonts/truetype/m7l; true ;;
        remove:m7l-*)       rm -rf "$LIBS_DIR/${c#m7l-}"; true ;;
        remove:zig)         rm -rf /opt/bsource/zig /root/.cache/zig ;;
        remove:nim)         pkg_del nim; rm -rf /root/.nimble ;;
        remove:dart)        rm -rf /opt/bsource/dart-sdk /root/.pub-cache ;;
        remove:haskell)     pkg_del ghc cabal-install; rm -rf /root/.cabal /root/.ghcup ;;
        remove:buildsys)    pkg_del scons ant just xmake nasm yasm ;;
        remove:devlibs)     pkg_del libsdl2-dev libboost-all-dev libglfw3-dev libglew-dev libvulkan-dev; apt-get autoremove -y >/dev/null 2>&1; true ;;
        remove:aptfile)     rm -rf /var/cache/apt/apt-file /root/.bsource/.aptfile; true ;;
        remove:gradlecache) rm -rf /root/.gradle/caches /root/.gradle/daemon /root/.gradle/wrapper/dists; true ;;
        remove:projects)    rm -rf /root/bsource-projects /root/bsource-zips; true ;;
        remove:caches)      # everything except the PlayStation toolchains (and the packages they need)
                            rm -rf "$SDK" /root/.gradle /root/.bsource/gradle /root/bsource-projects /root/bsource-zips \
                                   /root/.m2 /root/go /root/.cache "$TC/src" /root/.cargo /root/.rustup /root/.npm /opt/bsource/node \
                                   /root/.nuget /root/.dotnet /opt/bsource/dotnet /root/.bsource/flutter /root/.pub-cache /root/.bsource/pyenv "$LIBS_DIR" /tmp/bs_* "$BIN"/aapt* "$BIN"/zipalign
                            apt-get clean; true ;;
        *) echo "! unknown: $action $c"; false ;;
    esac
    if [ $? = 0 ]; then echo "✓ $c"; else echo "✗ $c"; fail=$((fail+1)); fi
done
stage 100 "Done"
[ "$fail" = 0 ] || die "$fail item(s) failed — see the log"
ok "done"
