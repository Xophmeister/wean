# wean

It occurred to me that agentic AI tools can exacerbate the feeling of
estrangement from ones own work, per Marx's [Theory of Alienation], and
are in direct contradiction to Naur's treatise of [Programming as Theory
Building]. (See my [original post] on this, on Mastodon.) Agentic AI
tools can be useful, but they shouldn't become a crux. So I wrote this
simple script to seize the means of production!

Rather than disabling these tools altogether, this script wraps them
with an exponential start-up timeout, resetting each calendar day, with
a helpful message to remind you that you should be in control of your
own work.

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

### I don't use NixOS

I gotchu, bro. The script is simple enough to modify for other
platforms, but I've included a [wrapper template script] that you can
use to wrap any binary you want:

1. Copy `wean.sh` and `wean-wrap-template.sh` to a location in your
   `$PATH`, which is of higher precedence than the original binary you
   want to wrap (e.g., `~/.local/bin`) and rename the template script to
   whatever you like (e.g., `claude`).

2. Modify the `WEAN` and `WRAP` variables in this copy to point to the
   path of `wean.sh` and the original binary you want to wrap (e.g.,
   `/usr/bin/claude`):

   ```diff
   @@ -2,8 +2,8 @@

    # wean.sh wrapper template for non-Nix systems

   -readonly WEAN="/path/to/wean.sh"
   -readonly WRAP="/path/to/wrapped/binary"
   +readonly WEAN="${HOME}/.local/bin/wean.sh"
   +readonly WRAP="/usr/bin/claude"

    [[ -x "${WEAN}" ]] || { echo "Error: wean.sh not found at ${WEAN}" >&2; exit 1; }
    [[ -x "${WRAP}" ]] || { echo "Error: wrapped binary not found at ${WRAP}" >&2; exit 1; }
   ```

3. Rinse and repeat for any other binaries you want to wrap.

## Isn't this trivial to bypass?

Yep. However, it's _probably_ easier to just run it than trying to
circumvent it. The idea is to provide enough friction to make you think
twice before reaching for agentic AI tools and, hopefully, building a
habit of re-engaging with your own work.

## Agent instructions

To try to further the point, I've included an example agent instruction
set to reinforce the idea that you should be in control of your own
work. Copy [this](/AGENTS.md.eg) wherever your agent of choice looks for
instructions.

<!-- Links -->

[theory of alienation]: https://en.wikipedia.org/wiki/Marx%27s_theory_of_alienation
[programming as theory building]: https://pablo.rauzy.name/dev/naur1985programming.pdf
[original post]: https://hachyderm.io/@xophmeister/116857208020117822
[nix]: https://nixos.org
[home-manager]: https://nix-community.github.io/home-manager
[wrapper template script]: /wean-wrap-template.sh
