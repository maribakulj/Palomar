# Palomar against the Yale Center for British Art's own Linked Art

The Yale Center for British Art (YCBA) publishes, for the same objects, LIDO
records over OAI-PMH and its own Linked Art, made by its own pipeline from its
collection database, not from its LIDO. Converting the LIDO with Palomar and
comparing the result with YCBA's Linked Art compares two independent mappings
of the same sources. The results are in `docs/eval/ycba.md`.

    D=/tmp/ycba && mkdir -p $D && curl -sS -o $D/p1.json \
      https://ycba-lux.s3.amazonaws.com/activity_stream/page1.json
    python3 -I dev/eval/ycba/sample.py $D 500           # the pairs (network)
    python3 -I dev/eval/ycba/people.py $D               # YCBA's people and their ids
    clojure -M dev/eval/ycba/convert.clj $D after.json  # Palomar's side, run from a checkout
    python3 -I dev/eval/ycba/compare.py $D after.json   # the counts, one column per output
    python3 -I dev/eval/ycba/diff_makers.py $D after.json

To compare two versions of Palomar on the same pairs, run this `convert.clj`
(by its absolute path, an older checkout's may take no output name) from
each checkout, with two output names. The sample is seeded.

The data is fetched, never committed. Data Source: Yale Center for British
Art, under the Center's
[Open Data and Data Services Terms of Use](https://britishart.yale.edu/open-data-and-data-services-terms-use).
