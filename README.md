# wean

It occurred to me that agentic AI tools can exacerbate the feeling of
estrangement from ones own work, per Marx's [Theory of Alienation], and
are in direct contradiction to Naur's treatise of [Programming as Theory
Building]. (See my [original post] on this, on Mastodon.) Agentic AI
tools can be useful, but they shouldn't become a crux. So I wrote this
simple script to seize the means of production!

Rather than disabling these tools altogether, this script wraps them
with an exponential timeout, resetting each calendar day -- with a
helpful message to remind you that you should be in control of your own
work -- before running.

## Usage

This is distributed as a [Nix] function, that can be used in your NixOS
configuration, for example, like so:

```nix
{ pkgs, ... }:

{
  environment.systemPackages = with pkgs; [
    (callPackage ./path/to/wean.nix {
        package = pkgs.claude-code;
        binary = "claude";
    })
  ];
}
```

or, with [Home-manager]:

```nix
home-manager.users.YOU.home.packages = with pkgs; [
  (callPackage ./path/to/wean.nix {
      package = pkgs.github-copilot-cli;
      binary = "copilot";
  })
];
```

<!-- FIXME This is not correct

### I don't use NixOS

The script is simple enough to modify for other platforms. I would
suggest something like this:

1. Copy `wean.sh` to a location in your `$PATH`, which is of higher
   precedence than the original binary you want to wrap (e.g.,
   `~/.local/bin/wean.sh`).

2. Modify the `BINARY` variable in `wean.sh` to instead point to `$0`:

   ```diff
   @@ -6,7 +6,7 @@

    set -euo pipefail

   -readonly BINARY='@BINARY@'
   +readonly BINARY="$0"
    readonly STATE="${XDG_STATE_HOME:-${HOME}/.local/state}/wean.json"

    today() {
   ```

3. Alongside the script, create a symlink to the original binary you
   want to wrap:

   ```bash
   ln -s /path/to/wean.sh claude
   ln -s /path/to/wean.sh copilot
   ```

-->

## Isn't this trivial to bypass?

Yep. However, it's _probably_ easier to just run it than trying to
circumvent it. The idea is to provide enough friction to make you think
twice before reaching for agentic AI tools and, hopefully, building a
habit of re-engaging with your own work.

<!-- Links -->

[theory of alienation]: https://en.wikipedia.org/wiki/Marx%27s_theory_of_alienation
[programming as theory building]: https://pablo.rauzy.name/dev/naur1985programming.pdf
[original post]: https://hachyderm.io/@xophmeister/116857208020117822
[nix]: https://nixos.org
[home-manager]: https://nix-community.github.io/home-manager
