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

## Amendment (lot O1, 2026-11-11): one guard for the paths of every command

- The L1 refusal was written for `convert` and copied, in part, into the
  other commands: `reconcile` compared `--manifest` with its input only, so
  `reconcile notices.xml --from intermarc --authorities autorites.edn
  --manifest autorites.edn` replaced the authority file with the manifest
  and exited 0, and `--manifest` naming `--model` replaced the model.
- Every command of the CLI now declares its file roles beside its entry in
  the dispatch table (`palomar.cli/commands`): what it reads (the input,
  the dump of `authorities index`, `--authorities`, `--model`) and what it
  writes (`--out`, `--manifest`). `run` checks them before the command
  runs, so no command reads or writes first and none can skip the check; a
  command without roles is not run, and a test fails for a command of the
  table whose roles are not stated.
- A written path that names the same file as another path of the run,
  read or written, is refused, exit 2, naming both roles and the path.
  Read paths are not compared with one another. "Same file" is L1's
  `same-file?`: the file system's identity when both exist (a symbolic or
  a hard link is the same file), else the canonical paths (the links of the
  directories followed; a dangling link names itself, where it is replaced),
  with case folded where the directory folds it.
- `-` names a file to this CLI, not a stream, and is compared as one; a
  device or a pipe is compared as a file, as `convert` did since L1
  (written into, not replaced, when it is used). stdin and stdout are used
  when a role is not given, and are not compared.
- The refusal names the command as typed (`authorities index`, `reconcile
  explain`) and the written path first, as L1 did (`--manifest is --out`).
  A read file that is not there is left to the command, which reports it as
  not found and writes nothing, before any comparison. A written path that
  is a directory is refused.
- Canonical paths are compared in Unicode NFC and, where the directory
  folds case, lower-cased in the root locale (`palomar.paths/path-key`). A
  path the JVM cannot encode (a name outside `sun.jnu.encoding`) cannot be
  compared, and the run is refused.
- The check is made again at publication (review of O1). At the check, the
  identity of each read file is recorded (its real path and its file key)
  and the written paths at which nothing is. Just before the output and the
  manifest are moved, each written path and the file it resolves to are
  checked again: one that now names a read file, by its real path or its
  key (a hard link), or that is a symbolic link where nothing was, is
  refused, exit 2, nothing published and the staged files removed.
- What remains: the window between that second check and the move itself,
  a race inside the final move, is not covered; nor is a written path that
  comes, during the run, to name the other written path, or one that comes
  to name a read file in `authorities index`, whose index is published by
  `palomar.authority-index/build!` without the second check.
- A link that names no path, `/dev/stdout` when stdout is a pipe
  (`/proc/self/fd/1` → `pipe:[…]`), is written into, as a device or a pipe
  is; before, resolving it failed the run.
- The dev tool that writes files, `dev/eval/ycba/convert.clj`, refuses an
  output name that is the input of a pair (`lido.xml`), by `same-file?`,
  before it writes anything. The Python scripts of `dev/eval/ycba` are not
  covered.

## Amendment (lot M6, 2026-11-09): the authorities a reconciliation matched against

- `reconcile --manifest <file>` writes a run manifest, as `convert` does: the
  input fingerprinted, the output (what was printed), the options, and under
  `:authorities` each authority file used with its path and fingerprint
  (`:sha256`, `:bytes`).
- For an authority index (ADR 0038 amendment M6) the entry also states,
  from the index header, `:index {:built :source {:name :sha256 :bytes}
  :format :blocking :entries}`: the build date, and the dump the index was
  built from as its header records it. The dump itself is not read again:
  it may be gone, or elsewhere.
- `diff` shows a changed index as a changed `:authorities` (another build
  date, another dump, or other bytes). Two reconciliations against the same
  index differ only in their times.
- The manifest is written beside its path and moved, as for `convert`; a
  `--manifest` that names the input, `--authorities` or `--model` is
  refused (lot O1).
