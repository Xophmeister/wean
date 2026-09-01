# wean

It occurred to me that agentic AI tools can exacerbate the feeling of
estrangement from ones own work, per Marx's [Theory of Alienation], and
are in direct contradiction to Naur's treatise of [Programming as Theory
Building]. (See my [original post] on this, on Mastodon.) Agentic AI
tools can be useful, but they shouldn't become a crux. So I wrote this
little tool to seize the means of production!

Rather than disabling these tools altogether, this script wraps them
with a start-up timeout that grows with how much you've used them over
the last 24 hours, along with a helpful message to remind you that you
should be in control of your own work.

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

I gotchu, bro. All you need is [Babashka] on your `$PATH`, plus a
directory that takes precedence over the binary you want to wrap:

1. Put `wean.clj` somewhere permanent:

   ```sh
   install -Dm755 wean.clj ~/.local/share/wean/wean.clj
   ```

2. Symlink it into a directory that comes _earlier_ in your `$PATH`
   than the real binary, with that binary's name:

   ```sh
   ln -s ~/.local/share/wean/wean.clj ~/.local/bin/claude
   ```

3. Rinse and repeat for anything else you want to wrap: one symlink
   each, all pointing at the same script.

wean works out what to run from the name it was invoked as. It looks
along `$PATH` for the next binary of that name which isn't itself, so
the symlink shadows the real `claude` and wean finds it immediately
behind. There's nothing to configure per binary.

If that's not what you want -- the real binary isn't on `$PATH`, or you
want to wrap it under a different name -- set `WEAN_BINARY` to its full
path and wean will use that instead. That's how the Nix route works:
the wrapper sets it for you.

## State

wean keeps a log at `$XDG_STATE_HOME/wean/log.edn` -- usually
`~/.local/state/wean/log.edn` -- with one entry per session per wrapped
binary, recording when it started and when it ended. Deleting it resets
the friction to nothing, which segues neatly to...

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
[babashka]: https://babashka.org
