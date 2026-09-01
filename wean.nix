{
  lib,
  writers,
  writeText,
  clj-kondo,
  makeBinaryWrapper,
  runCommand,
  package,
  binary ? package.pname or package.name,
}:

let
  # clj-kondo checks the namespace against the file name and the
  # writer's output is an extension-less file named after whatever it is
  # wrapping. The linter is otherwise worth keeping, so silence just
  # that one.
  #
  # Passed as a file because the check string is word-split by the
  # builder without quote removal, so no argument may contain a space,
  # and with --lint last because the script path is appended to it.
  #
  # Passed at all because writeBabashka's own default for check never
  # reaches makeScriptWriter, so omitting it lints nothing.
  kondo = writeText "kondo.edn" ''
    {:linters {:namespace-name-mismatch {:level :off}}}
  '';

  wean = writers.writeBabashkaBin "wean" {
    check = "${lib.getExe clj-kondo} --config ${kondo} --lint";
  } (builtins.readFile ./wean.clj);
in
runCommand binary
  {
    nativeBuildInputs = [ makeBinaryWrapper ];
    meta.mainProgram = binary;
  }
  ''
    makeWrapper ${wean}/bin/wean $out/bin/${binary} \
      --set WEAN_BINARY ${package}/bin/${binary}
  ''
