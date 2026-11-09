# 0030 — A conversion can write a manifest of everything its output depends on

- Status: Accepted
- Date: 2026-10-25

## Context

Palomar is deterministic — content-derived ids, no clock in the engine — but a
result carried no record of what produced it. Two outputs that differ could not
be explained: another input, another mapping, another version, another option?
For reproducible research a conversion has to say what it was.

## Decision

1. `palomar.manifest/manifest` builds an EDN map: the manifest's own version;
   Palomar's version and commit (a `palomar/build.edn` resource a release build
   writes, `{:version "dev"}` otherwise); the spoke and target; each plugin with
   its `:plugin/spec-version` and a canonical digest (`model/content-digest`) of
   its mapping and rules — they are data, so any change to a rule changes its
   digest; the vendored ontology files' SHA-256; the SHA-256 and size of every
   input and output; the options; the active rule ids; the loss summary; the run
   times; and an `:external` block for what an application did upstream (a model
   and its parameters).
2. The engine stays pure: times, paths and the external block come from the
   caller. `convert --manifest <file>` writes one.
3. `manifest/diff` names the top-level keys that differ between two manifests,
   ignoring the run times: empty means the runs reproduce each other.

## Alternatives considered

- *Embedding provenance in the output itself*: not every target can carry it
  (MARC, DC), and it would change the output it describes.
- *Recording the git commit at run time*: the commit of the working directory is
  not the code that ran when Palomar is used as a library; the build writes it.

## Consequences

- Two runs of the same conversion produce manifests whose `diff` is empty
  (pinned by `manifest-test`); a changed mapping is named.
- `build.edn` is not yet written by the release workflow; until it is, the
  version reads `"dev"`.

## Amendment (post-audit 8, K6)

- `convert --stream --manifest` writes the manifest too, as batch does. Its
  output is the file written, and its options say `:stream? true`. The
  manifest is written beside its path before `--out` is replaced: a manifest
  that cannot be written leaves `--out` as it was.
- An input that is no regular file (a pipe) is read once: the manifest names
  it, `:fingerprinted? false`, and does not read it again.
- A file is fingerprinted as it is read, in constant memory: a streamed
  dump's output may be larger than the heap.
- The manifest lists the vocabularies and lookup tables
  (`resources/palomar/vocab`), each fingerprinted (`:vocabularies`). They are
  data that decide what the rules and the code state, so they are part of
  what a run ran. When the resources are packed in a jar they were not
  listed; since L3 (review of 2026-11-09) they are found from the jar as from
  the source tree, beside an anchor (`palomar/vocab/lido/event-types.edn`),
  under whatever classpath root the jar has (`WEB-INF/classes/`), each named
  by its classpath path. Each is fingerprinted as the code reads it, through
  the classpath: a root that puts a vocabulary first is the one listed. A
  packaged run and a checkout list the same `:vocabularies`; their
  `[:palomar :code]` may differ, since a jar counts its resources as source.
  No vocabulary found beside the anchor is an error, never an empty list.


## Amendment (review of 2026-11-09, L1): three files, published in order

- `convert` refuses a run two of whose paths, the input, `--out` and
  `--manifest`, name one file, in batch and in stream. Two paths that exist are
  compared by the file system (`Files/isSameFile`: a symbolic or a hard link is
  the same file), others by their canonical paths. A refusal writes nothing.
  Before, `--manifest` naming the input replaced the source with the manifest,
  and `--manifest` naming `--out` replaced one with the other, both with exit 0.
  Where a directory folds case (macOS, Windows), two names that differ only
  in case are one file, even before either exists.
- The output and the manifest are written beside the files they replace,
  followed through their links, then moved, the output first, keeping the
  permissions and, where the user may, the owner of the file replaced. A link
  is written through and stays a link. A device or a pipe, or an existing file
  whose directory takes no new file, is written into, as before: no move is
  possible there, so a failed write may leave it in part. A hard link to the
  file replaced keeps the earlier bytes. The manifest fingerprints the
  output's staged file, so it names the bytes published.
- Two files cannot be replaced at once. When the output is published and the
  manifest cannot be, the run exits 2 and says so: the output is new, and no
  manifest describes it.
