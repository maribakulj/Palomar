# Palomar against the Yale Center for British Art's own Linked Art

The Yale Center for British Art (YCBA) publishes, for the same objects, LIDO
records (OAI-PMH, CC0) and its own Linked Art (made by its own pipeline from
its collection database, not from its LIDO). Converting the LIDO with Palomar
and comparing the result with YCBA's Linked Art is a comparison with an
independent mapping of the same sources (`docs/eval/rules-study.md`).

    mkdir -p /tmp/ycba && curl -sS -o /tmp/ycba/p1.json \
      https://ycba-lux.s3.amazonaws.com/activity_stream/page1.json
    python3 -I dev/eval/ycba/sample.py /tmp/ycba 40        # pairs, network
    clojure -M dev/eval/ycba/convert.clj /tmp/ycba          # Palomar's side
    python3 -I dev/eval/ycba/compare.py /tmp/ycba           # the counts

The data is fetched, never committed: YCBA's Linked Art states no license.
The sample is seeded (40 objects of the stream's first page).
