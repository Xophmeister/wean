# Wrap a package's binary with the "wean" nag script
#
# Invoke with `pkgs.callPackage`:
# ```nix
# pkgs.callPackage ./wean.nix { package = pkgs.something; }
# pkgs.callPackage ./wean.nix { package = pkgs.another-thing; binary = "foo"; }
# ```

{
  writeShellApplication,
  jq,
  coreutils,
  package,
  binary ? package.pname or package.name,
}:

writeShellApplication {
  name = binary;

  runtimeInputs = [
    jq
    coreutils
  ];

  text = builtins.replaceStrings [ "@BINARY@" ] [ "${package}/bin/${binary}" ] (
    builtins.readFile ./wean.sh
  );
}
