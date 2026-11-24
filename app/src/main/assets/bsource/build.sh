# Building source — build one Android Studio / Gradle project into an APK
# Input (environment):
#   BS_SRC      project folder or .zip          BS_NAME   project name (output folder)
#   BS_TYPE     debug | release                 BS_CLEAN  1 = clean first
#   BS_COPY     1 = build a private copy (fast, safe) / 0 = build in place
#   BS_MEM      Gradle memory in MB             BS_JAVA   17 | 21
#   BS_OFFLINE  1 = --offline                   BS_ARGS   extra Gradle arguments
#   BS_OUT      where the APKs are copied       BS_PIDFILE
. "$(dirname "$0")/common.sh"
echo $$ > "$BS_PIDFILE"

. "$(dirname "$0")/ps.sh"
. "$(dirname "$0")/langs.sh"
stage 2 "Checking tools"
pkg_get unzip zip curl tar xz-utils ca-certificates || die "Could not install unzip/curl (check the internet)"

stage 8 "Preparing the project"
WORK="$HOME/bsource-projects"
mkdir -p "$WORK"
# the private build copy is removed when the build ends (the results are already in the output folder)
cleanup_work() { case "${dest:-}" in "$WORK"/?*) cd / ; rm -rf "$WORK/$BS_NAME" 2>/dev/null ;; esac; }
trap cleanup_work EXIT
if [ -f "$BS_SRC" ]; then
    dest="$WORK/$BS_NAME"
    rm -rf "$dest"; mkdir -p "$dest"
    echo "› unzipping $(basename "$BS_SRC")"
    unzip -q -o "$BS_SRC" -d "$dest" || die "Could not unzip the project"
elif [ "$BS_COPY" = 1 ]; then
    dest="$WORK/$BS_NAME"
    mkdir -p "$dest"
    echo "› copying project to the build folder"
    if command -v rsync >/dev/null 2>&1; then
        rsync -a --delete --exclude 'build/' --exclude '.gradle/' --exclude '.idea/' --exclude '.cxx/' "$BS_SRC/" "$dest/" || die "Copy failed"
    else
        rm -rf "$dest"; mkdir -p "$dest"
        (cd "$BS_SRC" && tar --exclude='./build' --exclude='*/build' --exclude='.gradle' --exclude='.idea' --exclude='.cxx' -cf - .) | (cd "$dest" && tar -xf -) || die "Copy failed"
    fi
else
    dest="$BS_SRC"
fi

crlf_fix "$dest"
rm -f "$TMPDIR/bs_emit_$$"
TARGET="${BS_TARGET:-auto}"
[ "$TARGET" = auto ] && TARGET=$(detect_target "$dest")
echo "@@TARGET $TARGET"
echo "› target: $TARGET"
out="$BS_OUT/$BS_NAME"

case "$TARGET" in
    ps1|ps2|ps3|ps4|ps5|native)
        root=$(root_with "$dest" Makefile makefile GNUmakefile CMakeLists.txt meson.build configure configure.ac autogen.sh SConstruct)
        [ -n "$root" ] || root="$dest"
        cd "$root" || die "Cannot open $root"
        echo "› project: $root"
        [ "$BS_CLEAN" = 1 ] && { make clean >/dev/null 2>&1; rm -rf build bsout; }
        m7l_auto "$dest"
        marker="$TMPDIR/bs_marker_$$"; touch "$marker"; sleep 1
        tgt_fix=native; [ "$TARGET" = native ] || tgt_fix=console
        run_with_fixes "$tgt_fix" "build_$TARGET"; rc=$?
        stage 98 "Collecting the files"
        cd "$dest" || true
        collect_outputs "$marker" "$TARGET" "$out"
        n=$(cat "$TMPDIR/bs_count_$$" 2>/dev/null || echo 0); rm -f "$TMPDIR/bs_count_$$" "$marker"
        [ "$rc" = 0 ] || die "Build failed (exit $rc) — see the log"
        [ "$n" -gt 0 ] || die "Build finished but no output file (ELF/PKG/EXE) was found"
        stage 100 "Done"; ok "$n file(s) in $out"; exit 0 ;;
    rust|go|node|python|maven|gradlejava|java|dotnet|flutter|script|zig|nim|dart|haskell|ant)
        cd "$dest" || die "Cannot open $dest"
        [ "$BS_CLEAN" = 1 ] && rm -rf target bsout dist build bin/Release obj
        marker="$TMPDIR/bs_marker_$$"; touch "$marker"; sleep 1
        echo "› project: $dest"
        run_with_fixes native "build_$TARGET" "$dest"; rc=$?
        stage 98 "Collecting the files"
        BS_N=$(cat "$TMPDIR/bs_emit_$$" 2>/dev/null | wc -l)
        [ "$TARGET" = script ] && { cd "$dest"; for k in native any; do collect_outputs "$marker" $k "$out"; BS_N=$(( BS_N + $(cat "$TMPDIR/bs_count_$$" 2>/dev/null || echo 0) )); done; }
        rm -f "$marker" "$TMPDIR/bs_count_$$" "$TMPDIR/bs_emit_$$"
        [ "$rc" = 0 ] || die "Build failed (exit $rc) — see the log"
        [ "$BS_N" -gt 0 ] || die "Build finished but no output file was found"
        stage 100 "Done"; ok "$BS_N file(s) in $out"; exit 0 ;;
    reactnative)
        prepare_reactnative "$dest"
        [ -d android ] && dest="$PWD/android" ;;
    android) ;;
    *)
        if build_script "$dest"; then
            marker="$TMPDIR/bs_marker_$$"; touch -d '-1 hour' "$marker"
            collect_outputs "$marker" native "$out"; n=$(cat "$TMPDIR/bs_count_$$" 2>/dev/null || echo 0)
            [ "$n" -gt 0 ] && { stage 100 "Done"; ok "$n file(s) in $out"; exit 0; }
        fi
        die "Unknown project type — no Gradle, CMake, Makefile, Cargo, go.mod, package.json, pom.xml, .csproj or Python files found" ;;
esac

# ---------------------------------------------------------------- Android (Gradle)

# project root = the shallowest folder with settings.gradle(.kts), else build.gradle(.kts)
root=$(find "$dest" -maxdepth 5 \( -name settings.gradle -o -name settings.gradle.kts \) -not -path '*/build/*' 2>/dev/null | awk '{ print gsub("/","/"), $0 }' | sort -n | head -1 | cut -d' ' -f2-)
[ -z "$root" ] && root=$(find "$dest" -maxdepth 5 \( -name build.gradle -o -name build.gradle.kts \) -not -path '*/build/*' 2>/dev/null | awk '{ print gsub("/","/"), $0 }' | sort -n | head -1 | cut -d' ' -f2-)
[ -n "$root" ] || die "No Android project here (settings.gradle / build.gradle not found)"
root=$(dirname "$root")
cd "$root" || die "Cannot open $root"
echo "› project: $root"

# Java: the version that fits the project's Gradle / Android Gradle Plugin (old projects need Java 8 or 11)
if [ -z "$BS_JAVA" ] || [ "$BS_JAVA" = auto ]; then
    gw=$(wrapper_version); ag=$(agp_version)
    gma=${gw%%.*}; gmi=$(echo "$gw" | cut -d. -f2); ama=${ag%%.*}
    BS_JAVA=17
    case "$gma" in
        2|3|4) BS_JAVA=8 ;;
        5|6) BS_JAVA=11 ;;
        7) [ "${gmi:-0}" -lt 3 ] 2>/dev/null && BS_JAVA=11 ;;
    esac
    if [ -n "$ama" ] && [ "$ama" -lt 7 ] 2>/dev/null; then
        [ "$ama" -le 3 ] && BS_JAVA=8 || { [ "$BS_JAVA" = 17 ] && BS_JAVA=11; }
    fi
    echo "› Java $BS_JAVA (fits Gradle ${gw:-?} / AGP ${ag:-?})"
fi
pkg_get "openjdk-$BS_JAVA-jdk-headless" || die "Could not install Java $BS_JAVA (check the internet)"
ensure_aapt2 || die "Could not get an ARM build of aapt2 (check the internet)"
export JAVA_HOME="$(java_home "$BS_JAVA")"
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_OPTS="-Dorg.gradle.native=false -Dorg.gradle.vfs.watch=false"
java -version 2>&1 | head -1

stage 14 "Android SDK"
api=$(grep -rhoE --include='*.gradle' --include='*.kts' --include='gradle.properties' --include='*.toml' \
      'compileSdk(Version)?[[:space:]]*[=:(]?[[:space:]]*"?(android-)?[0-9]{2}' . 2>/dev/null | grep -oE '[0-9]{2}$' | sort -n | tail -1)
api="${api:-34}"
echo "› compileSdk $api"
ensure_platform "$api" || echo "! platform $api: Gradle will try to download it"
ensure_buildtools 35.0.0 || echo "! build-tools: Gradle will try to download them"
# old projects pin their own build-tools (buildToolsVersion "30.0.3")
for btv in $(grep -rhoE --include='*.gradle' --include='*.kts' 'buildToolsVersion[[:space:]]*[=(]?[[:space:]]*"[0-9.]+"' . 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -u); do
    [ "$btv" = 35.0.0 ] || ensure_buildtools "$btv" || echo "! build-tools $btv: Gradle will try to download them"
done
licenses

if grep -rqE --include='*.gradle' --include='*.kts' 'externalNativeBuild|ndkVersion|ndkBuild|cmake[[:space:]]*\{' . 2>/dev/null; then
    stage 17 "NDK (C/C++)"
    ensure_ndk || die "This project has C/C++ code and needs the NDK (Libraries → NDK). Its download failed."
    # ndk-build needs GNU make; CMake projects need cmake + ninja for ARM (Google's are x86)
    pkg_get make cmake ninja-build || die "Could not install make / cmake"
    export GNUMAKE=/usr/bin/make
    fix_ndk
    grep -rqE --include='*.gradle' --include='*.kts' 'CMakeLists\.txt|cmake[[:space:]]*\{' . 2>/dev/null && cmake_native=1
    want=$(grep -rhoE --include='*.gradle' --include='*.kts' --include='gradle.properties' 'ndkVersion[[:space:]]*[=:]?[[:space:]]*"?[0-9]+\.[0-9]+\.[0-9]+' . 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1)
    link_ndk "$want"
    for v in $(grep -rhoE --include='gradle.properties' '^ndkVersion=[0-9.]+' . 2>/dev/null | cut -d= -f2); do link_ndk "$v"; done
fi
fix_buildtools

# sdk.dir for this project
if [ -f local.properties ]; then sed -i '/^sdk.dir=/d' local.properties; fi
echo "sdk.dir=$SDK" >> local.properties
if [ "${cmake_native:-0}" = 1 ]; then
    sed -i '/^cmake.dir=/d' local.properties
    echo "cmake.dir=/usr" >> local.properties
fi

stage 20 "Gradle"
agp=$(agp_version)
[ -n "$agp" ] && echo "› Android Gradle Plugin $agp"
if [ -f gradlew ] && [ -f gradle/wrapper/gradle-wrapper.jar ]; then
    chmod +x gradlew 2>/dev/null
    G="bash ./gradlew"
    echo "› using the project's Gradle wrapper ($(wrapper_version))"
else
    # no wrapper: use the version from gradle-wrapper.properties, else the one that fits the AGP version
    gv="$BS_GRADLE"
    [ -z "$gv" ] || [ "$gv" = auto ] && gv=$(wrapper_version)
    [ -z "$gv" ] && gv=$(gradle_for_agp "$agp")
    echo "› no gradlew in the project → Gradle $gv"
    G=$(ensure_gradle_dist "$gv") || die "Could not download Gradle $gv (check the internet)"
    G="bash $G"
fi
if [ "$BS_TYPE" = release ]; then task="assembleRelease"; else task="assembleDebug"; fi
[ "$BS_CLEAN" = 1 ] && task="clean $task"
args="--no-daemon --console=plain --stacktrace --no-watch-fs -Pandroid.aapt2FromMavenOverride=$BIN/aapt2"
args="$args -Dorg.gradle.jvmargs=-Xmx${BS_MEM}m -Dorg.gradle.workers.max=2 -Dfile.encoding=UTF-8"
[ "$BS_OFFLINE" = 1 ] && args="$args --offline"
marker="$TMPDIR/bs_marker_$$"
touch "$marker"
sleep 1
echo "› $G $task $BS_ARGS"
# shellcheck disable=SC2086
glog="$TMPDIR/bs_gradle_$$.log"
$G $task $args $BS_ARGS 2>&1 | tee "$glog"
rc=${PIPESTATUS[0]}
# offline mode but the libraries were never downloaded → try again online
if [ "$rc" != 0 ] && grep -q "available for offline mode" "$glog"; then
    echo "› offline mode has no downloaded libraries yet → retrying online"
    args="${args/ --offline/} -Porg.gradle.offline=false"
    # shellcheck disable=SC2086
    $G $task $args $BS_ARGS 2>&1
    rc=$?
fi
rm -f "$glog"
echo "@@GRADLE_EXIT $rc"

stage 98 "Collecting the APK"
n=0
while IFS= read -r f; do
    mkdir -p "$out"
    cp -f "$f" "$out/" && { echo "@@APK $out/$(basename "$f")"; n=$((n+1)); }
done < <(find . -path '*/build/outputs/*' \( -name '*.apk' -o -name '*.aab' \) -newer "$marker" 2>/dev/null)
rm -f "$marker"
[ "$rc" = 0 ] || die "Gradle failed (exit $rc) — see the log"
[ "$n" -gt 0 ] || die "Build finished but no APK was produced"
stage 100 "Done"
ok "$n file(s) in $out"
