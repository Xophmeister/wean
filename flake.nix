{
  description = "wean: seize the means of production from our agentic overloads";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "aarch64-darwin"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});

      # Everything the QA tasks operate on, so that the checks and the
      # formatter cannot drift apart over which files they cover.
      clojure = "wean.clj wean_test.clj bb.edn";
      nix = "flake.nix";

      # A QA check: run the script against a writeable copy of the
      # sources and, if it succeeds, produce the output the build needs
      # to pass.
      qa =
        pkgs: name: packages: script:
        pkgs.runCommand "wean-check-${name}" { nativeBuildInputs = packages; } ''
          export HOME="$TMPDIR"
          cp -r ${self} source && chmod -R +w source && cd source
          ${script}
          touch "$out"
        '';
    in
    {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [
            pkgs.babashka-unwrapped
            pkgs.cljfmt
            pkgs.clj-kondo
            pkgs.nixfmt
          ];
        };
      });

      checks = forAllSystems (pkgs: {
        lint = qa pkgs "lint" [ pkgs.clj-kondo ] ''
          clj-kondo --lint ${clojure}
        '';

        format = qa pkgs "format" [ pkgs.cljfmt pkgs.nixfmt ] ''
          cljfmt check ${clojure}
          nixfmt --check ${nix}
        '';

        test = qa pkgs "test" [ pkgs.babashka-unwrapped ] ''
          bb test
        '';
      });

      formatter = forAllSystems (
        pkgs:
        pkgs.writeShellApplication {
          name = "wean-fmt";
          runtimeInputs = [
            pkgs.cljfmt
            pkgs.nixfmt
          ];
          text = ''
            cljfmt fix ${clojure}
            nixfmt ${nix}
          '';
        }
      );
    };
}
