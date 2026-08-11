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
      system = "x86_64-linux";
      # MUST come from the builder's own nixpkgs, not a locally-resolved one. The plugin
      # is compiled against the builder's Qt (6.9.2); pulling qthttpserver from any other
      # nixpkgs yields a different Qt (6.11.1 here) and the bundled lib then fails against
      # the runtime's Qt6Core at load time — a mismatch that only shows up on a real
      # machine, not in the build.
      pkgs = logos-module-builder.inputs.nixpkgs.legacyPackages.${system};
    in
    logos-module-builder.lib.mkLogosModule {
      src = ./.;
      configFile = ./metadata.json;
      flakeInputs = inputs;

      # Ship Qt6HttpServer next to the plugin.
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
      # The installed module dir has RUNPATH '$ORIGIN:$ORIGIN/.', so a plain copy beside
      # the plugin resolves. qtwebsockets comes along because HttpServer links it.
      #
      # Per logos_module_builder_bundling: the builder's installPhase bypasses cmake
      # install(), so postInstall is the supported hook for extra payload.
      postInstall = ''
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
      '';
    };
}
