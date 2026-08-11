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
    logos-module-builder.lib.mkLogosModule {
      src = ./.;
      configFile = ./metadata.json;
      flakeInputs = inputs;
    };
}
