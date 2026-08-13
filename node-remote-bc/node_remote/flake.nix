{
  description = "Minimal Logos Module - Example using logos-module-builder";

  inputs = {
    logos-module-builder.url = "github:logos-co/logos-module-builder";

    # blockchain_module ships no PREBUILT SDK header (unlike delivery/storage/chat),
    # so its typed wrapper must be generated from source at build time.
    #
    # THE INPUT ATTRIBUTE NAME MUST EQUAL THE DEPENDENCY STRING EXACTLY.
    # mkLogosModule.nix:160 does:
    #     moduleInputs = lib.filterAttrs (n: _: elem n legacyHeaderDepNames) flakeInputs;
    # so `blockchain-module` (hyphen) is silently discarded and the build then dies on a
    # missing blockchain_module_api.h with no hint that the input name was the problem.
    #
    # Pinned to 0.2.1 to match the installed blockchain_module. Bump in lockstep.
    blockchain_module.url = "github:logos-blockchain/logos-blockchain-module/0.2.1";
  };

  outputs = inputs@{ logos-module-builder, ... }:
    let
      # THE HOST SYSTEM, not a hardcoded one.
      #
      # This was `"x86_64-linux"`, which meant the bundling below pulled x86_64-linux
      # libraries no matter what was being built — so a darwin build shipped
      # libQt6HttpServer.so.6 inside a Mach-O bundle. Useless there, and it masked the real
      # problem (macOS Basecamp provides no QtHttpServer at all) behind files that looked
      # like they were doing the job.
      #
      # currentSystem makes this IMPURE — build with `--impure`. The consequence is the same
      # one receiver-basecamp documents: the catalog CI cannot auto-build it, so releases
      # propagate to the catalog manually. node-remote already publishes manually (its
      # modules live in subdirs of the submodule, which the stock action cannot build), so
      # this costs nothing new here.
      system = builtins.currentSystem;

      # MUST come from the builder's own nixpkgs, not a locally-resolved one. The plugin
      # is compiled against the builder's Qt (6.9.2); pulling qthttpserver from any other
      # nixpkgs yields a different Qt (6.11.1 here) and the bundled lib then fails against
      # the runtime's Qt6Core at load time — a mismatch that only shows up on a real
      # machine, not in the build. Same reasoning applies to tor and its closure.
      pkgs = logos-module-builder.inputs.nixpkgs.legacyPackages.${system};

      isDarwin = pkgs.stdenv.hostPlatform.isDarwin;

      # tor, shipped at <moduleDir>/bin/ so the .lgx is zero-install. See nix/tor-bundle.nix.
      torBundle = pkgs.callPackage ./nix/tor-bundle.nix { };
    in
    logos-module-builder.lib.mkLogosModule {
      src = ./.;
      configFile = ./metadata.json;
      flakeInputs = inputs;

      # Ship Qt6HttpServer next to the plugin, in the form the platform actually loads.
      #
      # VERIFIED ON A CLEAN MACHINE (khidr, no nix, 2026-08-11): without this the module
      # fails to load with
      #     Cannot load library node_remote_plugin.so:
      #     libQt6HttpServer.so.6: cannot open shared object file
      # The Basecamp/logoscore runtime supplies Qt6Core/Network/RemoteObjects but NOT
      # HttpServer — it is not a default Qt module, and nothing else in the ecosystem
      # pulls it in. `nix.packages.runtime` only makes it available inside the BUILD
      # sandbox; it does not put it in the .lgx.
      #
      # macOS needs the SAME fix in a different shape: Basecamp.app there ships 55 Qt
      # frameworks and QtHttpServer is not among them, and a Mach-O plugin loads a
      # FRAMEWORK, not a .so. Verified: the plugin's LC_RPATH is `@loader_path`, so a
      # framework copied beside it resolves — the darwin analogue of the $ORIGIN trick
      # the linux branch relies on.
      #
      # Per logos_module_builder_bundling: the builder's installPhase bypasses cmake
      # install(), so postInstall is the supported hook for extra payload.
      postInstall = ''
        # ── tor, on every platform ───────────────────────────────────────────────────
        # resolveTor() has always claimed the .lgx ships tor here. It did not: on Linux
        # the PATH fallback quietly found the system /bin/tor, so nothing bundled and
        # nobody noticed until a Mac (no tor, no Homebrew) produced tor_not_found.
        mkdir -p $out/lib/bin
        cp -a ${torBundle}/. $out/lib/bin/
        chmod -R u+w $out/lib/bin

        # ── Qt HttpServer/WebSockets, in the platform's own form ────────────────────
      '' + (if isDarwin then ''
        for fw in QtHttpServer QtWebSockets; do
          src=""
          for cand in ${pkgs.qt6.qthttpserver}/lib/$fw.framework \
                      ${pkgs.qt6.qtwebsockets}/lib/$fw.framework; do
            [ -d "$cand" ] && src="$cand"
          done
          if [ -n "$src" ]; then
            cp -a "$src" $out/lib/
          else
            echo "WARNING: $fw.framework not found — the module will not load on macOS" >&2
          fi
        done
        chmod -R u+w $out/lib
      '' else ''
        for lib in ${pkgs.qt6.qthttpserver}/lib/libQt6HttpServer.so.* \
                   ${pkgs.qt6.qtwebsockets}/lib/libQt6WebSockets.so.*; do
          [ -f "$lib" ] || continue
          cp -L "$lib" $out/lib/
        done
        chmod -R u+w $out/lib
        # Recreate the SONAME symlinks the loader actually asks for (libFoo.so.6).
        for f in $out/lib/libQt6HttpServer.so.*.*.* $out/lib/libQt6WebSockets.so.*.*.*; do
          [ -f "$f" ] || continue
          base=$(basename "$f")
          soname=''${base%.*.*}     # libQt6HttpServer.so.6.9.2 -> libQt6HttpServer.so.6
          ln -sf "$base" "$out/lib/$soname"
        done
      '');
    };
}
