# The self-contained tor shipped at <moduleDir>/bin/ inside the .lgx.
#
# node_remote runs a hidden SERVICE, so this is tor and nothing else — no privoxy or
# torsocks (receiver needs those because it is a Tor *client*), and no ffplay.
#
# WHY THIS EXISTS: resolveTor() falls back to PATH, and every Linux desktop happens to have
# /bin/tor — so the module appeared to work while bundling nothing at all. A Mac typically
# has no tor and no Homebrew, and the failure there is total: no onion, `tor_not_found`.
#
# Linux : copy tor + its non-glibc closure, rpath $ORIGIN. glibc is deliberately NOT bundled
#         — the system one is always present, and shipping a second one mixes loaders.
# Darwin: copy tor + its /nix/store dylib closure, rewrite install-names to @loader_path so
#         the bundle relocates, then AD-HOC CODESIGN. install_name_tool invalidates the
#         Mach-O signature and macOS refuses to run an unsigned-after-edit binary — miss that
#         step and the bundle is silently unusable. System dylibs (/usr/lib, /System) are
#         left alone; they are the mac analogue of "system glibc is always there".
#
# Modelled on receiver-basecamp's nix/helper-bundle.nix, which has shipped tor on macOS.
# Uses `stdenv` (not stdenvNoCC) so the darwin build env provides otool + install_name_tool.
{ stdenv, lib, tor, patchelf ? null, sigtool ? null }:

let isDarwin = stdenv.hostPlatform.isDarwin;
in
stdenv.mkDerivation {
  pname = "node-remote-tor-bundle";
  version = "1";
  dontUnpack = true;
  nativeBuildInputs = lib.optionals isDarwin [ sigtool ]
                   ++ lib.optionals (!isDarwin) [ patchelf ];

  buildCommand =
    if isDarwin then ''
      mkdir -p $out

      # Copy a Mach-O file and, recursively, its /nix/store dylib dependencies.
      collect() {
        otool -L "$1" | tail -n +2 | awk '{print $1}' | while read -r dep; do
          case "$dep" in
            /nix/store/*)
              b=$(basename "$dep")
              if [ ! -e "$out/$b" ]; then
                install -m755 "$dep" "$out/$b"
                collect "$out/$b"
              fi
              ;;
          esac
        done
      }

      install -m755 ${tor}/bin/tor $out/tor
      collect $out/tor

      # Make the bundle relocatable, then re-sign — in that order.
      for f in $out/*; do
        install_name_tool -id "@loader_path/$(basename "$f")" "$f" 2>/dev/null || true
        otool -L "$f" | tail -n +2 | awk '{print $1}' | while read -r dep; do
          case "$dep" in
            /nix/store/*) install_name_tool -change "$dep" "@loader_path/$(basename "$dep")" "$f" || true ;;
          esac
        done
        codesign -f -s - "$f" 2>/dev/null || true
      done
    '' else ''
      mkdir -p $out

      # Anything glibc-provided stays on the system: bundling a second glibc alongside the
      # host's is how you get a loader that refuses to start.
      GLIBC_SKIP='libc\.so|libm\.so|libdl\.so|libpthread\.so|librt\.so|ld-linux|libresolv|libstdc\+\+|libgcc_s'

      add() {
        local src; src=$(readlink -f "$1")
        install -m755 "$src" "$out/$(basename "$1")"
        ldd "$src" 2>/dev/null | awk '/=>/{print $3}' | while read -r libp; do
          [ -z "$libp" ] && continue
          b=$(basename "$libp")
          echo "$b" | grep -qE "$GLIBC_SKIP" && continue
          [ -e "$out/$b" ] || install -m755 "$(readlink -f "$libp")" "$out/$b"
        done
      }

      add ${tor}/bin/tor

      # Resolve siblings from the bundle itself rather than from /nix/store, so the .lgx
      # works on a machine with no nix.
      for f in $out/*; do
        patchelf --set-rpath '$ORIGIN' "$f" 2>/dev/null || true
      done

      # AND POINT THE EXECUTABLE AT THE SYSTEM LOADER. A nix-built binary's PT_INTERP is a
      # /nix/store path (here /lib/ld-linux-x86-64.so.2, which does not exist on a normal
      # distro — the real one is /lib64/...). Without this, running it fails with
      # "No such file or directory" even though the file is plainly there and ldd resolves
      # every library: the message is about the missing INTERPRETER, not the binary, which
      # makes it easy to misread. Libraries have no PT_INTERP, so only tor is patched.
      patchelf --set-interpreter /lib64/ld-linux-x86-64.so.2 "$out/tor" 2>/dev/null || true
    '';
}
