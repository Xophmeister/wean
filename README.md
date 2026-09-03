# wean

It occurred to me that agentic AI tools can exacerbate the feeling of
estrangement from ones own work, per Marx's [Theory of Alienation], and
are in direct contradiction to Naur's treatise of [Programming as Theory
Building]. (See my [original post] on this, on Mastodon.) Agentic AI
tools can be useful, but they shouldn't become a crux. So I wrote this
little tool to seize the means of production!

Rather than disabling these tools altogether, this script wraps them
with a start-up timeout that grows with how much you've leant on them
lately -- both how often you've reached for them and how long you've
kept them running -- along with a helpful message to remind you that you
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

## Methodology

The wait is a function of two things: how often you've launched the
tool, and how long you've kept it running. Recent use counts for more
than old and the whole thing is bounded, so that wean never becomes so
obstructive that deleting it is the rational move.

### Nothing expires; it fades

wean doesn't count usage inside a fixed window. Every session is
weighted by its age instead, with an exponential decay:

![The decay of a session's weight with its age](/doc/decay.svg)

A hard window is a cliff. Under a rolling week, last Monday's marathon
stops counting _this_ Monday and your friction drops for no reason you
earned; a free pass on a schedule you could learn and time your work
around. Decaying the weight removes the cliff: old sessions never stop
counting, they just matter less.

`default-window` is therefore a mean lifetime rather than a cut-off. A
week's window puts the half-life a shade under five days.

Time spent is _integrated_ across a session rather than weighted at its
start, so the older part of a long session is discounted against its
newer. That has a pleasant consequence: a session you never close
converges on exactly one window's worth and stops growing. Leaving one
open forever is bounded, not infinite.

### One number, two habits

The two measurements are traded against each other at a fixed rate and
added together:

```
score = launches + time spent / session-equivalent
```

`session-equivalent` (30 minutes) is that rate, and it reads as a
question in English: _how long may a session run before it counts as
another launch?_

Both terms are needed, because the friction is a start-up cost and
nothing else. Once the nag is paid, keeping a session open is free, so
counting launches alone makes the cheapest strategy a single session
opened when you wake up and abandoned when you go to bed. That is more
use of the tool for less friction, which is precisely backwards. The
exchange rate decides which habit comes off worst: set it too generously
and someone churning through short sessions is punished harder than
someone who never closes one at all.

Consider the following three habits to make that concrete; each a week's
worth, at the point where the decay has settled:

|                        |                          | Score |
| :--------------------- | :----------------------- | :---- |
| A light week           | 5 sessions of 20 minutes | 8     |
| A heavy week           | 40 short sessions        | 47    |
| One long session a day | 7 sessions of 8 hours    | 119   |

The last is what the duration term exists to catch. Under the old
formula, which counted launches over a calendar day, it cost exactly the
same as the lightest.

### The curve

The score is fed through a logistic:

![The wait, against the usage score](/doc/friction.svg)

Gentle while usage is ordinary, steep once it isn't, then levelling off
rather than climbing forever.

That bound is deliberate. The obvious alternative -- keep doubling, as
the original did -- reaches hours within a fortnight and a wait long
enough to be worth circumventing buys no deterrence at all: the bypass
is a single `rm`. A wean that's been deleted measures nothing.

A hard cap has the opposite defect. Past the cap, more usage is free:
you've paid the toll, so you may as well carry on. A logistic approaches
its ceiling without ever quite reaching it, so there is always a little
more to pay.

### Turning the knobs

Two constants shape the curve and they do (almost) independent jobs.

`friction-anchors` is a pair of `[score, seconds]` opinions: what a
light week and a heavy one ought to cost. The steepness and midpoint are
_solved for_ from them rather than written down, because `0.0645` and
`84.04` are numbers nobody can sanity-check, whereas "ten seconds after
a light week" is a judgement you can actually hold. `max-friction` is
the ceiling.

Moving the ceiling barely disturbs the anchored region:

| `max-friction` | Light week | Heavy week | One long session a day |
| :------------- | :--------- | :--------- | :--------------------- |
| 10 minutes     | 9 s        | 2 min      | 10 min                 |
| 20 minutes     | 9 s        | 2 min      | 18 min                 |
| 30 minutes     | 9 s        | 2 min      | 26 min                 |

...and moving the anchors barely disturbs the ceiling's:

| `friction-anchors`   | Light week | Heavy week | One long session a day |
| :------------------- | :--------- | :--------- | :--------------------- |
| `[[10 5] [50 60]]`   | 4 s        | 49 s       | 16 min                 |
| `[[10 10] [50 120]]` | 9 s        | 2 min      | 18 min                 |
| `[[10 20] [50 240]]` | 18 s       | 3 min      | 19 min                 |

So the anchors set how the everyday feels and the ceiling sets what the
worst case costs; you can tune either without upsetting the other.
Along with `default-window` and `session-equivalent`, they all live in
one block at the top of `wean.clj`.

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
